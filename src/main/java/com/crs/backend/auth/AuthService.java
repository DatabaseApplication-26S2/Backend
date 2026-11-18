package com.crs.backend.auth;

import com.crs.backend.domain.entity.*;
import com.crs.backend.domain.enums.*;
import com.crs.backend.adapter.in.web.dto.*;
import com.crs.backend.queue.QueueProperties;
import jakarta.persistence.*;
import org.redisson.api.RedissonClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {
    @PersistenceContext private EntityManager em;
    private final LoginCredentialStore credentials;
    private final RedissonClient redis;
    private final QueueProperties queue;
    private final TokenCodec tokens;
    private final JdbcTemplate jdbc;
    private final SessionGuard guard;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(10);
    // Shared intake lock: queue owners must use this same key before allocating ticket_id_seq.
    public static final long INTAKE_LOCK = 20260001L;
    public AuthService(LoginCredentialStore credentials, RedissonClient redis, QueueProperties queue,
                       TokenCodec tokens, JdbcTemplate jdbc, SessionGuard guard) {
        this.credentials = credentials; this.redis = redis; this.queue = queue; this.tokens = tokens;
        this.jdbc = jdbc; this.guard = guard;
    }
    public static String randomToken() {
        byte[] value = new byte[32]; new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant(); }
    private void intakeLock() { jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> {}, INTAKE_LOCK); }
    private void publish(long id) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                // Redis is only a mirror. A failed publish must not turn a committed acceptance into an HTTP failure.
                try { redis.getScoredSortedSet(queue.getWaitingKey()).add((double) id, Long.toString(id)); }
                catch (RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(AuthService.class).warn("Queue mirror publish failed for ticket {}", id); }
            }
        });
    }

    @Transactional
    public LoginReceipt login(String userId, char[] password) { return login(userId, password, null); }

    @Transactional
    public LoginReceipt login(String userId, char[] password, String existingProof) {
        if (userId == null || userId.isEmpty()) throw AuthFailure.invalid(com.crs.backend.common.ApiProblem.Source.BODY, "/userId");
        if (password == null || password.length == 0) throw AuthFailure.invalid(com.crs.backend.common.ApiProblem.Source.BODY, "/password");
        intakeLock();
        if (existingProof != null) {
            var active = jdbc.queryForList("""
                SELECT ticket_id FROM tickets WHERE request_type='LOGIN' AND anon_proof_verifier=?
                AND proof_expires_at>clock_timestamp() AND status_cd IN ('RECEIVED','WAITING','PROCESSING')
                """, Long.class, hash(existingProof));
            if (!active.isEmpty()) throw AuthFailure.pending(active.getFirst());
        }
        String context = UUID.randomUUID().toString(), proof = randomToken();
        Instant at = now();
        long id = jdbc.queryForObject("""
            INSERT INTO tickets(request_type, anon_context_id, anon_proof_verifier, credential_expires_at,
                proof_expires_at, ticket_issued_at, status_cd)
            VALUES ('LOGIN', CAST(? AS uuid), ?, ?, ?, ?, 'RECEIVED') RETURNING ticket_id
            """, Long.class, context, hash(proof), Timestamp.from(at.plusSeconds(300)),
            Timestamp.from(at.plusSeconds(600)), Timestamp.from(at));
        credentials.put(context, userId, password, at.plusSeconds(300));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { if (status != STATUS_COMMITTED) credentials.discard(context); }
        });
        jdbc.update("UPDATE tickets SET status_cd='WAITING' WHERE ticket_id=?", id);
        publish(id);
        return new LoginReceipt(Long.toString(id), TicketRequestType.LOGIN, TicketStatus.WAITING, at, "/api/v1/tickets/" + id, proof);
    }

    /** Compatibility adapter for the existing dispatcher; a stale worker never consumes credentials. */
    @Transactional
    public void processLogin(WaitingTicket ticket) {
        int expectedGeneration = ticket.getGenerationNo();
        String expectedWorker = ticket.getOwnerWorkerRef();
        em.flush();
        em.refresh(ticket, LockModeType.PESSIMISTIC_WRITE);
        if (ticket.getStatus() != TicketStatus.PROCESSING || expectedGeneration != ticket.getGenerationNo()
            || !Objects.equals(expectedWorker, ticket.getOwnerWorkerRef())) return;
        UserAccount user = credentials.consume(ticket.getAnonContextId(), credential -> {
            UserAccount account = em.find(UserAccount.class, credential.userId());
            if (account == null) return null;
            try {
                if (!passwords.matches(CharBuffer.wrap(credential.password()), account.getPasswordHash())) return null;
            } catch (IllegalArgumentException ex) { return null; }
            return account;
        }, null);
        Instant at = now();
        if (user == null || !at.isBefore(ticket.getCredentialExpiresAt())) {
            finish(ticket, expectedGeneration, "E01"); return;
        }
        jdbc.update("""
            INSERT INTO sessions(session_id,user_id,role_cd,login_ticket_id,logged_in_at,expires_at)
            VALUES (CAST(? AS uuid),?,?,?,CAST(? AS timestamptz),CAST(? AS timestamptz)+interval '168 hours')
            """, UUID.randomUUID().toString(), user.getUserId(), user.getRole().name(), ticket.getTicketId(),
            Timestamp.from(at), Timestamp.from(at));
        finish(ticket, expectedGeneration, "OK");
    }

    private void finish(WaitingTicket ticket, int generation, String code) {
        int changed = jdbc.update("""
            UPDATE tickets SET status_cd=?, result_cd=?, processing_finished_at=clock_timestamp(),
                owner_worker_ref=NULL,lease_info=NULL,slot_claim_ref=NULL
            WHERE ticket_id=? AND status_cd='PROCESSING' AND generation_no=? AND owner_worker_ref=?
            """, code.equals("OK") ? "SUCCEEDED" : "REJECTED", code, ticket.getTicketId(), generation, ticket.getOwnerWorkerRef());
        if (changed != 1) throw new IllegalStateException("Stale ticket completion");
        em.refresh(ticket);
    }

    private WaitingTicket ownedTicket(long id, String proof, boolean lock) {
        WaitingTicket ticket = lock ? em.find(WaitingTicket.class, id, LockModeType.PESSIMISTIC_WRITE) : em.find(WaitingTicket.class, id);
        if (ticket == null || ticket.getRequestType() != TicketRequestType.LOGIN || proof == null
            || !MessageDigest.isEqual(hash(proof).getBytes(StandardCharsets.US_ASCII),
                ticket.getAnonProofVerifier().getBytes(StandardCharsets.US_ASCII)))
            throw new AuthFailure(403, "LOGIN_PROOF_INVALID");
        if (!now().isBefore(ticket.getProofExpiresAt())) throw new AuthFailure(403, "LOGIN_PROOF_EXPIRED");
        return ticket;
    }
    @Transactional(readOnly = true)
    public void authorizeTicket(long id, String proof, CurrentUser user) {
        WaitingTicket ticket = em.find(WaitingTicket.class, id);
        if (ticket == null) throw new AuthFailure(403, "FORBIDDEN");
        if (ticket.getRequestType() == TicketRequestType.LOGIN) {
            try { ownedTicket(id, proof, false); } catch (AuthFailure ex) { throw new AuthFailure(403, "FORBIDDEN"); }
            return;
        }
        if (user == null || !user.userId().equals(ticket.getRequesterUserId())) throw new AuthFailure(403, "FORBIDDEN");
    }

    @Transactional
    public IssuedTokens exchange(long id, String proof) {
        WaitingTicket ticket = ownedTicket(id, proof, true);
        if (ticket.isIncomplete()) throw AuthFailure.pending(id);
        if (ticket.getStatus() == TicketStatus.REJECTED) throw new AuthFailure(401, "E01");
        String sid = jdbc.queryForObject("SELECT session_id::text FROM sessions WHERE login_ticket_id=?", String.class, id);
        var session = guard.lock(sid);
        if (session.firstIssuedAt() != null) throw new AuthFailure(403, "LOGIN_EXCHANGE_CONSUMED");
        session.requireValid();
        if (jdbc.update("UPDATE sessions SET first_token_issued_at=clock_timestamp() WHERE session_id=CAST(? AS uuid) AND first_token_issued_at IS NULL", sid) != 1)
            throw new AuthFailure(403, "LOGIN_EXCHANGE_CONSUMED");
        return issue(session);
    }
    private IssuedTokens issue(SessionGuard.Snapshot session) {
        String refresh = randomToken();
        jdbc.update("INSERT INTO refresh_tokens(refresh_token_hash,session_id,issued_at) VALUES (?,CAST(? AS uuid),clock_timestamp())", hash(refresh), session.id());
        var access = tokens.issue(session.id(), session.userId(), session.role(), session.expiresAt(), now());
        return new IssuedTokens(access.value(), refresh, session.expiresAt(), new UserView(session.userId(), session.name(), session.role()),
            new SessionView(session.expiresAt(), session.admittedAt()), access.expiresIn());
    }
    @Transactional(noRollbackFor = AuthFailure.class)
    public IssuedTokens refresh(String raw) {
        String sid = refreshSession(raw);
        if (sid == null) throw new AuthFailure(401, "REFRESH_TOKEN_INVALID");
        var session = guard.lock(sid);
        boolean used = Boolean.TRUE.equals(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM refresh_tokens WHERE refresh_token_hash=?", Boolean.class, hash(raw)));
        session.requireValid();
        if (used) { revoke(sid, "REFRESH_REUSE"); throw new AuthFailure(401, "REFRESH_TOKEN_REUSED"); }
        jdbc.update("UPDATE refresh_tokens SET consumed_at=clock_timestamp() WHERE refresh_token_hash=?", hash(raw));
        return issue(session);
    }
    private String refreshSession(String raw) {
        if (raw == null) return null;
        var ids = jdbc.queryForList("SELECT session_id::text FROM refresh_tokens WHERE refresh_token_hash=?", String.class, hash(raw));
        return ids.isEmpty() ? null : ids.getFirst();
    }
    private void revoke(String id, String reason) {
        jdbc.update("UPDATE sessions SET session_status='REVOKED',revoked_at=clock_timestamp(),revoke_reason=? WHERE session_id=CAST(? AS uuid)", reason, id);
    }
    @Transactional
    public void logout(String raw) { logout(raw, null); }
    @Transactional
    public void logout(String raw, String authorization) {
        String id = refreshSession(raw);
        if (raw == null && authorization != null && authorization.startsWith("Bearer ")) {
            try { id = tokens.decode(authorization.substring(7)); } catch (AuthFailure ignored) { return; }
        }
        if (id == null) return;
        try {
            var session = guard.lock(id); session.requireValid();
            revoke(id, "LOGOUT");
        } catch (AuthFailure ignored) { /* Idempotent, including expired sessions. */ }
    }
    @Transactional(readOnly = true)
    public CurrentUser authenticate(String bearer) {
        var claims = tokens.decodeClaims(bearer);
        var session = guard.snapshot(claims.getClaimAsString("sid"));
        session.requireValid();
        if (!session.userId().equals(claims.getSubject())) throw new AuthFailure(401, "E02");
        return new CurrentUser(session.userId(), session.id(), session.role());
    }
    @Transactional(readOnly = true)
    public boolean isAdmitted(String userId, String sessionId) {
        var session = guard.snapshot(sessionId); session.requireStudent(userId);
        return session.admittedAt() != null;
    }
    @Transactional
    public Object requestAdmission(String userId, String sessionId) {
        intakeLock();
        var session = guard.lock(sessionId); session.requireStudent(userId);
        if (session.admittedAt() != null) return new AdmissionState(session.admittedAt());
        var active = jdbc.queryForList("SELECT ticket_id FROM tickets WHERE requester_user_id=? AND status_cd IN ('RECEIVED','WAITING','PROCESSING')", Long.class, userId);
        if (!active.isEmpty()) throw AuthFailure.pending(active.getFirst());
        Instant at = now();
        long id = jdbc.queryForObject("""
            INSERT INTO tickets(request_type,requester_user_id,session_ref,ticket_issued_at,status_cd)
            VALUES ('ADMISSION',?,CAST(? AS uuid),?,'RECEIVED') RETURNING ticket_id
            """, Long.class, userId, sessionId, Timestamp.from(at));
        jdbc.update("UPDATE tickets SET status_cd='WAITING' WHERE ticket_id=?", id);
        publish(id);
        return new TicketReceipt(Long.toString(id), TicketRequestType.ADMISSION, TicketReceiptStatus.WAITING, at, "/api/v1/tickets/" + id);
    }
    @Transactional
    public void processAdmission(WaitingTicket ticket) {
        int generation = ticket.getGenerationNo();
        String worker = ticket.getOwnerWorkerRef();
        em.flush(); em.refresh(ticket, LockModeType.PESSIMISTIC_WRITE);
        if (ticket.getStatus() != TicketStatus.PROCESSING || generation != ticket.getGenerationNo()
            || !Objects.equals(worker, ticket.getOwnerWorkerRef())) return;
        try {
            var session = guard.lock(ticket.getSessionRef()); session.requireStudent(ticket.getRequesterUserId());
            jdbc.update("UPDATE sessions SET entered_at=COALESCE(entered_at,clock_timestamp()) WHERE session_id=CAST(? AS uuid)", session.id());
        } catch (AuthFailure ex) { finish(ticket, generation, "E02"); return; }
        finish(ticket, generation, "OK");
    }
    public record LoginReceipt(String ticketId, TicketRequestType requestType, TicketStatus status,
                               Instant issuedAt, String statusUrl, String proof) {
        @Override public String toString() { return "LoginReceipt[redacted]"; }
    }
    public record UserView(String userId, String name, UserRole role) {}
    public record SessionView(Instant expiresAt, Instant admittedAt) {}
    public record IssuedTokens(String accessToken, String refreshToken, Instant sessionExpiresAt, UserView user, SessionView session, long expiresIn) {
        @Override public String toString() { return "IssuedTokens[redacted]"; }
    }
}
