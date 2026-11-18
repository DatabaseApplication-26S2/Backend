package com.crs.backend.queue;

import com.crs.backend.adapter.in.web.dto.Ticket;
import com.crs.backend.adapter.in.web.dto.TicketReceipt;
import com.crs.backend.adapter.in.web.dto.TicketResult;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.TicketDispatcher;
import com.crs.backend.application.port.TicketIntake;
import com.crs.backend.auth.AuthService;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketStatus;
import com.crs.backend.domain.exception.EnrollmentException;
import com.crs.backend.domain.exception.ErrorCode;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대기열 파사드.
 * 입장 접수는 AuthService(현재 세션의 sessions.entered_at, 접수 잠금), 신청·취소 접수는 TicketIntake,
 * 배정은 TicketDispatcher(DB claim). LOGIN 접수는 AuthService가 담당한다.
 */
@Service
public class QueueService {

    private final AuthService authService;
    private final WaitingTicketJpaRepository waitingTicketRepository;
    private final TicketIntake ticketIntake;
    private final TicketDispatcher ticketDispatcher;

    public QueueService(
            AuthService authService,
            WaitingTicketJpaRepository waitingTicketRepository,
            TicketIntake ticketIntake,
            @Lazy TicketDispatcher ticketDispatcher
    ) {
        this.authService = authService;
        this.waitingTicketRepository = waitingTicketRepository;
        this.ticketIntake = ticketIntake;
        this.ticketDispatcher = ticketDispatcher;
    }

    /**
     * POST /admission — 현재 세션이 이미 입장했으면 AdmissionState, 아니면 ADMISSION 티켓 접수.
     */
    @Transactional
    public Object requestAdmission(String userId, String sessionId) {
        return authService.requestAdmission(userId, sessionId);
    }

    /**
     * GET /tickets/{ticketId}
     * WAITING 순번은 동일 DB 스냅샷에서 앞선 WAITING 수 + 1이다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
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

    /**
     * 스케줄러·테스트 진입점. DB claim + 핸들러 동기 실행.
     */
    public int admitWaitingUsers() {
        return ticketDispatcher.claimAndDispatch();
    }

    private Long parseTicketId(String ticketId) {
        if (ticketId == null || !ticketId.matches("^[1-9][0-9]*$")) {
            throw new EnrollmentException(ErrorCode.E09, "Invalid ticketId");
        }
        return Long.valueOf(ticketId);
    }
}
