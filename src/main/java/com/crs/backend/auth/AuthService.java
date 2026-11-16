package com.crs.backend.auth;

import com.crs.backend.domain.entity.*;
import com.crs.backend.domain.enums.*;
import com.crs.backend.queue.QueueProperties;
import jakarta.persistence.*;
import org.redisson.api.RedissonClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

@Service
public class AuthService {
    @PersistenceContext private EntityManager em;
    private final LoginCredentialStore credentials;
    private final RedissonClient redis;
    private final QueueProperties queue;
    private final TokenCodec tokens;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder(10);
    public AuthService(LoginCredentialStore credentials, RedissonClient redis, QueueProperties queue, TokenCodec tokens) {
        this.credentials = credentials; this.redis = redis; this.queue = queue; this.tokens = tokens;
    }

    public static String randomToken() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    @Transactional
    public LoginReceipt login(String userId, char[] password) {
        if (userId == null || userId.isBlank() || userId.length() > 20 || password == null || password.length == 0 || password.length > 256)
            throw new AuthFailure(400, "E09");
        String context = UUID.randomUUID().toString();
        String proof = randomToken();
        Instant now = Instant.now();
        WaitingTicket ticket = WaitingTicket.builder().requestType(TicketRequestType.LOGIN)
            .anonContextId(context).anonProofVerifier(hash(proof)).credentialExpiresAt(now.plusSeconds(300))
            .proofExpiresAt(now.plusSeconds(600)).ticketIssuedAt(now).status(TicketStatus.WAITING).build();
        credentials.put(context, userId, password);
        em.persist(ticket);
        em.flush();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                redis.getScoredSortedSet(queue.getWaitingKey()).add((double) ticket.getTicketIssuedAt().toEpochMilli(), ticket.ticketIdAsString());
            }
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) credentials.discard(context);
            }
        });
        return new LoginReceipt(ticket.ticketIdAsString(), TicketRequestType.LOGIN, TicketStatus.WAITING,
            now, "/api/v1/tickets/" + ticket.ticketIdAsString(), proof);
    }

    @Transactional
    public void processLogin(WaitingTicket ticket) {
        em.lock(ticket, LockModeType.PESSIMISTIC_WRITE);
        if (!ticket.isIncomplete()) return;
        UserAccount user = credentials.consume(ticket.getAnonContextId(), credential -> {
            UserAccount account = em.find(UserAccount.class, credential.userId());
            if (account == null || !passwords.matches(CharBuffer.wrap(credential.password()), account.getPasswordHash())) return null;
            return account;
        }, null);
        if (user == null) { ticket.reject(TicketResultCode.E01); return; }
        Instant now = Instant.now();
        AccessSession session = AccessSession.builder().sessionId(UUID.randomUUID().toString())
            .userId(user.getUserId()).role(user.getRole()).loginTicketId(ticket.getTicketId())
            .loggedInAt(now).expiresAt(now.plusSeconds(604800)).sessionStatus(SessionStatus.ACTIVE).build();
        em.persist(session);
        ticket.succeed();
    }

    private WaitingTicket ownedTicket(long id, String proof) {
        WaitingTicket ticket = em.find(WaitingTicket.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (ticket == null || ticket.getRequestType() != TicketRequestType.LOGIN || proof == null
            || !MessageDigest.isEqual(hash(proof).getBytes(StandardCharsets.US_ASCII),
                ticket.getAnonProofVerifier().getBytes(StandardCharsets.US_ASCII))) throw new AuthFailure(403, "LOGIN_PROOF_INVALID");
        if (!Instant.now().isBefore(ticket.getProofExpiresAt())) throw new AuthFailure(403, "LOGIN_PROOF_EXPIRED");
        return ticket;
    }

    @Transactional
    public void authorizeTicket(long id, String proof, CurrentUser user) {
        WaitingTicket ticket = em.find(WaitingTicket.class, id);
        if (ticket == null) throw new AuthFailure(403, "E02");
        if (ticket.getRequestType() == TicketRequestType.LOGIN) { ownedTicket(id, proof); return; }
        if (user == null || !user.userId().equals(ticket.getRequesterUserId())) throw new AuthFailure(403, "E02");
    }

    @Transactional
    public IssuedTokens exchange(long id, String proof) {
        WaitingTicket ticket = ownedTicket(id, proof);
        if (ticket.getStatus() == TicketStatus.REJECTED) throw new AuthFailure(401, "E01");
        if (ticket.getStatus() != TicketStatus.SUCCEEDED) throw new AuthFailure(409, "E03");
        AccessSession session = em.createQuery("select s from AccessSession s where s.loginTicketId = :id", AccessSession.class)
            .setParameter("id", id).setLockMode(LockModeType.PESSIMISTIC_WRITE).getSingleResult();
        if (!session.isValid(Instant.now())) throw new AuthFailure(401, "E02");
        if (session.getFirstTokenIssuedAt() != null) throw new AuthFailure(403, "LOGIN_EXCHANGE_CONSUMED");
        session.markTokensIssued(Instant.now());
        return issue(session);
    }

    private IssuedTokens issue(AccessSession session) {
        String refresh = randomToken();
        em.persist(RefreshTokenRecord.builder().refreshTokenHash(hash(refresh)).sessionId(session.getSessionId())
            .issuedAt(Instant.now()).build());
        return new IssuedTokens(tokens.encode(session), refresh, session.getExpiresAt());
    }

    @Transactional(noRollbackFor = AuthFailure.class)
    public IssuedTokens refresh(String raw) {
        RefreshTokenRecord record = raw == null ? null : em.find(RefreshTokenRecord.class, hash(raw));
        if (record == null) throw new AuthFailure(401, "E02");
        AccessSession session = em.find(AccessSession.class, record.getSessionId(), LockModeType.PESSIMISTIC_WRITE);
        em.refresh(record);
        if (session == null || !session.isValid(Instant.now())) throw new AuthFailure(401, "E02");
        if (record.isConsumed()) {
            session.revoke(RevokeReason.REFRESH_REUSE);
            throw new AuthFailure(401, "E02");
        }
        record.consume(Instant.now());
        return issue(session);
    }

    @Transactional
    public void logout(String raw) {
        RefreshTokenRecord record = raw == null ? null : em.find(RefreshTokenRecord.class, hash(raw));
        if (record == null) return;
        AccessSession session = em.find(AccessSession.class, record.getSessionId(), LockModeType.PESSIMISTIC_WRITE);
        if (session != null && session.isValid(Instant.now())) session.revoke(RevokeReason.LOGOUT);
    }

    @Transactional(readOnly = true)
    public CurrentUser authenticate(String bearer) {
        String id = tokens.decode(bearer);
        AccessSession session = em.find(AccessSession.class, id);
        if (session == null || !session.isValid(Instant.now())) throw new AuthFailure(401, "E02");
        UserAccount user = em.find(UserAccount.class, session.getUserId());
        if (user == null) throw new AuthFailure(401, "E02");
        return new CurrentUser(user.getUserId(), id, user.getRole());
    }

    public record LoginReceipt(String ticketId, TicketRequestType requestType, TicketStatus status,
                               Instant issuedAt, String statusUrl, String proof) {}
    public record IssuedTokens(String accessToken, String refreshToken, Instant sessionExpiresAt) {}
}
