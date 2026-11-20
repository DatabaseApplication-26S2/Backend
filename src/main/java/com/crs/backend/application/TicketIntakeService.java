package com.crs.backend.application;

import com.crs.backend.adapter.in.web.dto.TicketReceipt;
import com.crs.backend.adapter.in.web.dto.TicketReceiptStatus;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.port.TicketIntake;
import com.crs.backend.auth.AuthService;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketStatus;
import com.crs.backend.domain.exception.EnrollmentException;
import com.crs.backend.domain.exception.ErrorCode;
import com.crs.backend.queue.RedisWaitingOrderCopy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * QUE-01-01 신청·취소 접수(구현계획 4절 (1)·(2), B안). 한 트랜잭션에서 접수 잠금 → 같은 사용자의 미완료 조회(있으면 E03)
 * → 접수 시각 clock_timestamp() → RECEIVED 저장 → WAITING → 커밋 후 Redis(score=ticketId) 순으로 한다.
 * 입장·LOGIN 접수는 AuthService가 같은 접수 잠금으로 맡는다. 잠금 없는 접수 경로를 두지 않는다(plan/intake-lock.md C1).
 */
@Service
public class TicketIntakeService implements TicketIntake {

    public static final String STATUS_URL_PREFIX = "/api/v1/tickets/";
    private static final Set<TicketStatus> INCOMPLETE = EnumSet.of(
            TicketStatus.RECEIVED, TicketStatus.WAITING, TicketStatus.PROCESSING
    );

    private final WaitingTicketJpaRepository waitingTicketRepository;
    private final RedisWaitingOrderCopy waitingOrderCopy;
    private final JdbcTemplate jdbc;

    public TicketIntakeService(
            WaitingTicketJpaRepository waitingTicketRepository,
            RedisWaitingOrderCopy waitingOrderCopy,
            JdbcTemplate jdbc
    ) {
        this.waitingTicketRepository = waitingTicketRepository;
        this.waitingOrderCopy = waitingOrderCopy;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public TicketReceipt intakeEnrollment(String userId, String sessionId, String courseInstId, String courseCode) {
        validateUserId(userId);
        intakeLock();
        rejectIfIncomplete(userId);
        Instant issuedAt = issuedAt();
        return persistAndPublish(WaitingTicket.enrollment(userId, sessionId, courseInstId, courseCode, issuedAt));
    }

    @Override
    @Transactional
    public TicketReceipt intakeCancellation(String userId, String sessionId, String courseInstId, String courseCode) {
        validateUserId(userId);
        intakeLock();
        rejectIfIncomplete(userId);
        Instant issuedAt = issuedAt();
        return persistAndPublish(WaitingTicket.cancellation(userId, sessionId, courseInstId, courseCode, issuedAt));
    }

    /**
     * 접수 잠금(구현계획 4절 (1) 2번, D-06). 이 트랜잭션의 첫 SQL이다. 입장·LOGIN 접수와 같은 키
     * {@link AuthService#INTAKE_LOCK}을 트랜잭션 잠금으로 잡아 커밋까지 쥔다. 그래서 번호(INSERT 때 nextval) 순서와
     * 커밋 순서가 같고, 미완료 조회와 저장 사이에 같은 사용자의 다른 접수가 끼어들지 않는다.
     */
    private void intakeLock() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> {}, AuthService.INTAKE_LOCK);
    }

    /**
     * 접수 시각(구현계획 4절 (1)): 접수 잠금을 얻은 뒤 DB 시계 clock_timestamp()를 읽는다.
     * now()는 트랜잭션 시작 시각이라 잠금을 기다리기 전 값이 되어 번호 순서와 어긋날 수 있다.
     */
    private Instant issuedAt() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    private TicketReceipt persistAndPublish(WaitingTicket ticket) {
        WaitingTicket saved = waitingTicketRepository.saveAndFlush(ticket);
        saved.markWaiting();
        waitingTicketRepository.saveAndFlush(saved);
        waitingOrderCopy.publishAfterCommit(saved.getTicketId());
        return new TicketReceipt(
                saved.ticketIdAsString(),
                saved.getRequestType(),
                TicketReceiptStatus.WAITING,
                saved.getTicketIssuedAt(),
                STATUS_URL_PREFIX + saved.ticketIdAsString()
        );
    }

    /**
     * 미완료 유일성(구현계획 4절 (2) B안): 접수 잠금 아래에서 같은 사용자의 RECEIVED·WAITING·PROCESSING 티켓을 찾는다.
     * 있으면 그 번호를 activeTicketId로 실어 409 E03을 던지고 트랜잭션은 롤백된다.
     */
    private void rejectIfIncomplete(String userId) {
        Optional<WaitingTicket> active = waitingTicketRepository
                .findFirstByRequesterUserIdAndStatusIn(userId, INCOMPLETE);
        if (active.isPresent()) {
            throw new EnrollmentException(ErrorCode.E03, active.get().getTicketId());
        }
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new EnrollmentException(ErrorCode.E09, "userId must not be blank");
        }
    }
}
