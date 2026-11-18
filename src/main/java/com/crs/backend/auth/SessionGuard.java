package com.crs.backend.auth;
import com.crs.backend.domain.enums.UserRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Native locks return scalars; never flush a stale JPA session after a native update. */
@Component
public class SessionGuard {
    private final JdbcTemplate jdbc;
    public SessionGuard(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional(propagation = Propagation.MANDATORY)
    public Snapshot lock(String id) { return read(id, true); }
    @Transactional(readOnly = true)
    public Snapshot snapshot(String id) { return read(id, false); }
    private Snapshot read(String id, boolean lock) {
        try { UUID.fromString(id); } catch (RuntimeException e) { throw new AuthFailure(401, "E02"); }
        var rows = jdbc.query("""
            SELECT s.*, u.role_cd AS current_role, u.user_name, clock_timestamp() AS db_now
            FROM sessions s JOIN users u ON u.user_id=s.user_id WHERE s.session_id=CAST(? AS uuid)
            """ + (lock ? " FOR NO KEY UPDATE OF s" : ""), (rs, n) -> new Snapshot(
                rs.getString("session_id"), rs.getString("user_id"), rs.getString("user_name"),
                UserRole.valueOf(rs.getString("current_role")), rs.getString("session_status"),
                instant(rs, "expires_at"), instant(rs, "entered_at"), instant(rs, "first_token_issued_at"),
                instant(rs, "db_now")), id);
        if (rows.isEmpty()) throw new AuthFailure(401, "E02");
        var row = rows.getFirst();
        // Evaluate the deadline after a potentially long row-lock wait.
        Instant checkedAt = jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
        return new Snapshot(row.id(), row.userId(), row.name(), row.role(), row.status(), row.expiresAt(),
            row.admittedAt(), row.firstIssuedAt(), checkedAt);
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    public record Snapshot(String id, String userId, String name, UserRole role, String status,
                           Instant expiresAt, Instant admittedAt, Instant firstIssuedAt, Instant now) {
        public void requireValid() {
            if ("REVOKED".equals(status)) throw new AuthFailure(401, "SESSION_REVOKED");
            if (!now.isBefore(expiresAt)) throw new AuthFailure(401, "SESSION_EXPIRED");
        }
        public void requireStudent(String expectedUser) {
            requireValid();
            if (!userId.equals(expectedUser) || role != UserRole.STUDENT) throw new AuthFailure(403, "FORBIDDEN");
        }
    }
}
