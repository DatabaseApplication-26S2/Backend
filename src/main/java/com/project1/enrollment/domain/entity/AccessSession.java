package com.project1.enrollment.domain.entity;

import com.project1.enrollment.domain.enums.RevokeReason;
import com.project1.enrollment.domain.enums.SessionStatus;
import com.project1.enrollment.domain.enums.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * ENT-06 접속 세션.
 * 물리명: session_id, user_id, role_cd, login_ticket_id, logged_in_at, expires_at,
 * session_status, revoked_at, revoke_reason, first_token_issued_at, entered_at
 */
@Entity
@Table(name = "access_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessSession {

    @Id
    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_cd", length = 20, nullable = false)
    private UserRole role;

    @Column(name = "login_ticket_id", nullable = false)
    private Long loginTicketId;

    @Column(name = "logged_in_at", nullable = false)
    private Instant loggedInAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "session_status", nullable = false)
    private SessionStatus sessionStatus;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason")
    private RevokeReason revokeReason;

    @Column(name = "first_token_issued_at")
    private Instant firstTokenIssuedAt;

    /** 화면 입장 시각. null이면 미입장(E15) */
    @Column(name = "entered_at")
    private Instant enteredAt;

    @Builder
    public AccessSession(
            String sessionId,
            String userId,
            UserRole role,
            Long loginTicketId,
            Instant loggedInAt,
            Instant expiresAt,
            SessionStatus sessionStatus,
            Instant revokedAt,
            RevokeReason revokeReason,
            Instant firstTokenIssuedAt,
            Instant enteredAt
    ) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.role = role;
        this.loginTicketId = loginTicketId;
        this.loggedInAt = loggedInAt;
        this.expiresAt = expiresAt;
        this.sessionStatus = sessionStatus;
        this.revokedAt = revokedAt;
        this.revokeReason = revokeReason;
        this.firstTokenIssuedAt = firstTokenIssuedAt;
        this.enteredAt = enteredAt;
    }

    public boolean isValid(Instant now) {
        return sessionStatus == SessionStatus.ACTIVE && now.isBefore(expiresAt);
    }

    public boolean isAdmitted() {
        return enteredAt != null;
    }

    public void admit(Instant at) {
        this.enteredAt = at;
    }

    public void revoke(RevokeReason reason) {
        this.sessionStatus = SessionStatus.REVOKED;
        this.revokedAt = Instant.now();
        this.revokeReason = reason;
    }
}
