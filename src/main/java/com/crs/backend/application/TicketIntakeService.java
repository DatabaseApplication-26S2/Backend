package com.crs.backend.application;

import com.crs.backend.adapter.in.web.dto.TicketReceipt;
import com.crs.backend.adapter.in.web.dto.TicketReceiptStatus;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.port.TicketIntake;
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
 * QUE-01-01 입장·신청·취소 접수. RECEIVED → WAITING → 커밋 후 Redis(score=ticketId).
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
    public TicketReceipt intakeAdmission(String userId, String sessionId) {
        validateUserId(userId);
        rejectIfIncomplete(userId);
        return persistAndPublish(WaitingTicket.admission(userId, sessionId, issuedAt()));
    }

    @Override
    @Transactional
    public TicketReceipt intakeEnrollment(String userId, String sessionId, String courseInstId, String courseCode) {
        validateUserId(userId);
        rejectIfIncomplete(userId);
        return persistAndPublish(WaitingTicket.enrollment(userId, sessionId, courseInstId, courseCode, issuedAt()));
    }

    @Override
    @Transactional
    public TicketReceipt intakeCancellation(String userId, String sessionId, String courseInstId, String courseCode) {
        validateUserId(userId);
        rejectIfIncomplete(userId);
        return persistAndPublish(WaitingTicket.cancellation(userId, sessionId, courseInstId, courseCode, issuedAt()));
    }

    /** 접수 시각(구현계획 4절 (1)): 접수 트랜잭션 안에서 DB 시계 clock_timestamp()를 읽는다 */
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
