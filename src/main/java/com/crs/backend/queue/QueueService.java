package com.crs.backend.queue;

import com.crs.backend.adapter.in.web.dto.AdmissionState;
import com.crs.backend.adapter.in.web.dto.Ticket;
import com.crs.backend.adapter.in.web.dto.TicketReceipt;
import com.crs.backend.adapter.in.web.dto.TicketReceiptStatus;
import com.crs.backend.adapter.in.web.dto.TicketResult;
import com.crs.backend.adapter.out.persistence.jpa.AccessSessionRepository;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.domain.entity.AccessSession;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketRequestType;
import com.crs.backend.domain.enums.TicketResultCode;
import com.crs.backend.domain.enums.TicketStatus;
import com.crs.backend.domain.exception.EnrollmentException;
import com.crs.backend.domain.exception.ErrorCode;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSetCache;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 기존 수강신청 접수와 LOGIN/ADMISSION DB 처리 어댑터.
 * <ul>
 *   <li>member = ticketId (양의 10진수 문자열)</li>
 *   <li>인증 티켓 score = ticketId; 입장 상태는 sessions.entered_at</li>
 * </ul>
 */
@Service
public class QueueService {

    @org.springframework.beans.factory.annotation.Autowired private com.crs.backend.auth.AuthService authService;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private static final String STATUS_URL_PREFIX = "/api/v1/tickets/";
    private static final Set<TicketStatus> INCOMPLETE = EnumSet.of(
            TicketStatus.RECEIVED, TicketStatus.WAITING, TicketStatus.PROCESSING
    );

    private final RedissonClient redissonClient;
    private final QueueProperties properties;
    private final WaitingTicketJpaRepository waitingTicketRepository;
    private final AccessSessionRepository accessSessionRepository;

    public QueueService(
            RedissonClient redissonClient,
            QueueProperties properties,
            WaitingTicketJpaRepository waitingTicketRepository,
            AccessSessionRepository accessSessionRepository
    ) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.waitingTicketRepository = waitingTicketRepository;
        this.accessSessionRepository = accessSessionRepository;
    }

    /**
     * POST /admission — 이미 입장이면 AdmissionState, 아니면 ADMISSION 티켓 접수.
     */
    @Transactional
    public Object requestAdmission(String userId, String sessionId) {
        return authService.requestAdmission(userId, sessionId);
    }

    /**
     * GET /tickets/{ticketId}
     * WAITING 순번은 동일 DB 스냅샷에서 앞선 WAITING 수 + 1이다.
     */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Ticket getTicket(String ticketId) {
        Long id = parseTicketId(ticketId);
        WaitingTicket ticket = waitingTicketRepository.findById(id)
                .orElseThrow(() -> new EnrollmentException(ErrorCode.E02, "Ticket not found"));

        if (ticket.getStatus() == TicketStatus.WAITING) {
            Integer queuePosition = jdbc.queryForObject("SELECT count(*)+1 FROM tickets WHERE status_cd='WAITING' AND ticket_id<?", Integer.class, id);
            return toTicketDto(ticket, queuePosition, null);
        }

        if (ticket.getStatus() == TicketStatus.SUCCEEDED || ticket.getStatus() == TicketStatus.REJECTED) {
            TicketResult result = new TicketResult(ticket.getResultCode(), ticket.getProcessingFinishedAt());
            return toTicketDto(ticket, null, result);
        }

        return toTicketDto(ticket, null, null);
    }

    @Transactional
    public TicketReceipt createEnrollmentTicket(
            String userId,
            String sessionId,
            String courseInstId,
            String courseCode
    ) {
        return createCourseTicket(userId, sessionId, courseInstId, courseCode, TicketRequestType.ENROLLMENT);
    }

    @Transactional
    public TicketReceipt createCancellationTicket(
            String userId,
            String sessionId,
            String courseInstId,
            String courseCode
    ) {
        return createCourseTicket(userId, sessionId, courseInstId, courseCode, TicketRequestType.CANCELLATION);
    }

    public void removeFromWaitingQueue(String ticketId) {
        waitingQueue().remove(ticketId);
    }

    private TicketReceipt createCourseTicket(
            String userId,
            String sessionId,
            String courseInstId,
            String courseCode,
            TicketRequestType type
    ) {
        validateUserId(userId);
        if (waitingTicketRepository.existsByRequesterUserIdAndStatusIn(userId, INCOMPLETE)) {
            throw new EnrollmentException(ErrorCode.E03);
        }

        WaitingTicket ticket = waitingTicketRepository.save(
                type == TicketRequestType.ENROLLMENT
                        ? WaitingTicket.enrollment(userId, sessionId, courseInstId, courseCode)
                        : WaitingTicket.builder()
                        .requestType(TicketRequestType.CANCELLATION)
                        .requesterUserId(userId)
                        .sessionRef(sessionId)
                        .targetCourseInstId(courseInstId)
                        .courseCodeSnapshot(courseCode)
                        .ticketIssuedAt(Instant.now())
                        .status(TicketStatus.WAITING)
                        .retryCount(0)
                        .generationNo(0)
                        .build()
        );
        waitingQueue().add((double) System.currentTimeMillis(), ticket.ticketIdAsString());

        return new TicketReceipt(
                ticket.ticketIdAsString(),
                type,
                TicketReceiptStatus.WAITING,
                ticket.getTicketIssuedAt(),
                STATUS_URL_PREFIX + ticket.ticketIdAsString()
        );
    }

    /**
     * 인증 티켓만 DB에서 처리한다. 공용 TicketHandler 디스패처 연동 시 이 어댑터를 대체한다.
     */
    @Transactional
    public int admitWaitingUsers() {
        // Transitional LOGIN/ADMISSION adapter until the shared TicketHandler dispatcher lands.
        // DB owns acceptance and progress; Redis outages cannot strand authentication tickets.
        int limit = jdbc.queryForObject("SELECT concurrency_limit FROM service_settings WHERE singleton=true FOR NO KEY UPDATE", Integer.class);
        int occupied = jdbc.queryForObject("SELECT count(*) FROM tickets WHERE status_cd='PROCESSING'", Integer.class);
        var ids = jdbc.queryForList("SELECT ticket_id FROM tickets WHERE status_cd='WAITING' AND request_type IN ('LOGIN','ADMISSION') ORDER BY ticket_id LIMIT ? FOR UPDATE", Long.class, Math.max(0, limit-occupied));
        for (Long id : ids) {
            WaitingTicket ticket = waitingTicketRepository.findById(id).orElseThrow();
            ticket.markProcessing();
            waitingTicketRepository.flush();
            if (ticket.getRequestType() == TicketRequestType.LOGIN) authService.processLogin(ticket);
            else authService.processAdmission(ticket);
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { waitingQueue().remove(Long.toString(id)); }
                    catch (RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(QueueService.class).warn("Queue mirror removal failed for ticket {}", id); }
                }
            });
        }
        return ids.size();
    }
    private Ticket toTicketDto(WaitingTicket ticket, Integer queuePosition, TicketResult result) {
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

    private RScoredSortedSet<String> waitingQueue() {
        return redissonClient.getScoredSortedSet(properties.getWaitingKey());
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
