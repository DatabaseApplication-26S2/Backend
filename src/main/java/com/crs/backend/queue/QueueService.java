package com.crs.backend.queue;

import com.crs.backend.adapter.in.web.dto.AdmissionState;
import com.crs.backend.adapter.in.web.dto.Ticket;
import com.crs.backend.adapter.in.web.dto.TicketReceipt;
import com.crs.backend.adapter.in.web.dto.TicketResult;
import com.crs.backend.adapter.out.persistence.jpa.AccessSessionRepository;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.application.port.TicketIntake;
import com.crs.backend.domain.entity.AccessSession;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketStatus;
import com.crs.backend.domain.exception.EnrollmentException;
import com.crs.backend.domain.exception.ErrorCode;
import org.redisson.api.RSetCache;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 대기열 파사드.
 * 신청·취소 접수는 TicketIntake, 배정은 TicketDispatcher(DB claim).
 * LOGIN 접수는 AuthService가 담당한다.
 */
@Service
public class QueueService {

    private final RedissonClient redissonClient;
    private final QueueProperties properties;
    private final WaitingTicketJpaRepository waitingTicketRepository;
    private final AccessSessionRepository accessSessionRepository;
    private final TicketIntake ticketIntake;
    private final TicketDispatcher ticketDispatcher;

    public QueueService(
            RedissonClient redissonClient,
            QueueProperties properties,
            WaitingTicketJpaRepository waitingTicketRepository,
            AccessSessionRepository accessSessionRepository,
            TicketIntake ticketIntake,
            @Lazy TicketDispatcher ticketDispatcher
    ) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.waitingTicketRepository = waitingTicketRepository;
        this.accessSessionRepository = accessSessionRepository;
        this.ticketIntake = ticketIntake;
        this.ticketDispatcher = ticketDispatcher;
    }

    @Transactional
    public Object requestAdmission(String userId, String sessionId) {
        validateUserId(userId);

        if (isActive(userId)) {
            Instant admittedAt = accessSessionRepository.findById(sessionId)
                    .map(AccessSession::getEnteredAt)
                    .orElse(Instant.now());
            return new AdmissionState(admittedAt != null ? admittedAt : Instant.now());
        }

        return ticketIntake.intakeAdmission(userId, sessionId);
    }

    /**
     * GET /tickets/{ticketId}
     * queuePosition = DB에서 앞선 WAITING 수 + 1
     */
    @Transactional(readOnly = true)
    public Ticket getTicket(String ticketId) {
        Long id = parseTicketId(ticketId);
        WaitingTicket ticket = waitingTicketRepository.findById(id)
                .orElseThrow(() -> new EnrollmentException(ErrorCode.E02, "Ticket not found"));

        Integer queuePosition = null;
        TicketResult result = null;

        if (ticket.getStatus() == TicketStatus.WAITING) {
            long ahead = waitingTicketRepository.countWaitingAhead(ticket.getTicketId());
            queuePosition = (int) ahead + 1;
        }

        if (ticket.getStatus() == TicketStatus.SUCCEEDED || ticket.getStatus() == TicketStatus.REJECTED) {
            result = new TicketResult(ticket.getResultCode(), ticket.getProcessingFinishedAt());
        }

        return new Ticket(
                ticket.ticketIdAsString(),
                ticket.getRequestType(),
                ticket.getCourseCodeSnapshot(),
                ticket.getTicketIssuedAt(),
                ticket.getStatus(),
                queuePosition,
                result
        );
    }

    public TicketReceipt createEnrollmentTicket(
            String userId,
            String sessionId,
            String courseInstId,
            String courseCode
    ) {
        return ticketIntake.intakeEnrollment(userId, sessionId, courseInstId, courseCode);
    }

    public TicketReceipt createCancellationTicket(
            String userId,
            String sessionId,
            String courseInstId,
            String courseCode
    ) {
        return ticketIntake.intakeCancellation(userId, sessionId, courseInstId, courseCode);
    }

    public void removeFromWaitingQueue(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return;
        }
        redissonClient.getScoredSortedSet(properties.getWaitingKey()).remove(ticketId);
    }

    public boolean isActive(String userId) {
        return activeUsers().contains(userId);
    }

    /**
     * 스케줄러·테스트 진입점. DB claim + 핸들러 동기 실행.
     */
    public int admitWaitingUsers() {
        return ticketDispatcher.claimAndDispatch();
    }

    private RSetCache<String> activeUsers() {
        return redissonClient.getSetCache(properties.getActiveKey());
    }

    private Long parseTicketId(String ticketId) {
        if (ticketId == null || !ticketId.matches("^[1-9][0-9]*$")) {
            throw new EnrollmentException(ErrorCode.E09, "Invalid ticketId");
        }
        return Long.valueOf(ticketId);
    }

    private void validateUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new EnrollmentException(ErrorCode.E09, "userId must not be blank");
        }
    }
}
