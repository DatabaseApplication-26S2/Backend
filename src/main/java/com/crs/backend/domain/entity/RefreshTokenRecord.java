package com.crs.backend.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * ENT-07 리프레시 토큰 기록.
 * 물리명: refresh_token_hash, session_id, issued_at, consumed_at
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshTokenRecord {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR) // char(64): SHA-256 16진
    @Column(name = "refresh_token_hash", length = 64, nullable = false)
    private String refreshTokenHash;

    @Column(name = "session_id", nullable = false, columnDefinition = "uuid")
    private String sessionId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Builder
    public RefreshTokenRecord(String refreshTokenHash, String sessionId, Instant issuedAt, Instant consumedAt) {
        this.refreshTokenHash = refreshTokenHash;
        this.sessionId = sessionId;
        this.issuedAt = issuedAt;
        this.consumedAt = consumedAt;
    }

    public void consume(Instant at) {
        this.consumedAt = at;
    }

    public boolean isConsumed() {
        return consumedAt != null;
    }
}
