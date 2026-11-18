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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    public TicketIntakeService(
            WaitingTicketJpaRepository waitingTicketRepository,
            RedisWaitingOrderCopy waitingOrderCopy
    ) {
        this.waitingTicketRepository = waitingTicketRepository;
        this.waitingOrderCopy = waitingOrderCopy;
    }

    @Override
    @Transactional
    public TicketReceipt intakeAdmission(String userId, String sessionId) {
        validateUserId(userId);
        rejectIfIncomplete(userId);
        return persistAndPublish(WaitingTicket.admission(userId, sessionId));
    }

    @Override
    @Transactional
    public TicketReceipt intakeEnrollment(String userId, String sessionId, String courseInstId, String courseCode) {
        validateUserId(userId);
        rejectIfIncomplete(userId);
        return persistAndPublish(WaitingTicket.enrollment(userId, sessionId, courseInstId, courseCode));
    }

    @Override
    @Transactional
    public TicketReceipt intakeCancellation(String userId, String sessionId, String courseInstId, String courseCode) {
        validateUserId(userId);
        rejectIfIncomplete(userId);
        return persistAndPublish(WaitingTicket.cancellation(userId, sessionId, courseInstId, courseCode));
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
