package com.project1.enrollment.queue;

import com.project1.enrollment.adapter.in.web.dto.AdmissionState;
import com.project1.enrollment.adapter.in.web.dto.Ticket;
import com.project1.enrollment.adapter.in.web.dto.TicketReceipt;
import com.project1.enrollment.adapter.in.web.dto.TicketReceiptStatus;
import com.project1.enrollment.adapter.in.web.dto.TicketResult;
import com.project1.enrollment.adapter.out.persistence.jpa.AccessSessionRepository;
import com.project1.enrollment.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.project1.enrollment.domain.entity.AccessSession;
import com.project1.enrollment.domain.entity.WaitingTicket;
import com.project1.enrollment.domain.enums.TicketRequestType;
import com.project1.enrollment.domain.enums.TicketResultCode;
import com.project1.enrollment.domain.enums.TicketStatus;
import com.project1.enrollment.domain.exception.EnrollmentException;
import com.project1.enrollment.domain.exception.ErrorCode;
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
 * Redis ZSET 기반 대기열.
 * <ul>
 *   <li>member = ticketId (양의 10진수 문자열)</li>
 *   <li>score = {@code System.currentTimeMillis()}</li>
 * </ul>
 */
@Service
public class QueueService {

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
        validateUserId(userId);

        if (isActive(userId)) {
            Instant admittedAt = accessSessionRepository.findById(sessionId)
                    .map(AccessSession::getEnteredAt)
                    .orElse(Instant.now());
            return new AdmissionState(admittedAt != null ? admittedAt : Instant.now());
        }

        if (waitingTicketRepository.existsByRequesterUserIdAndStatusIn(userId, INCOMPLETE)) {
            throw new EnrollmentException(ErrorCode.E03);
        }

        WaitingTicket ticket = waitingTicketRepository.save(
                WaitingTicket.admission(userId, sessionId)
        );

        // ZSET: score = 현재 시각, member = ticketId
        waitingQueue().add((double) System.currentTimeMillis(), ticket.ticketIdAsString());

        Integer rank = waitingQueue().rank(ticket.ticketIdAsString());
        TicketReceiptStatus receiptStatus = rank == null
                ? TicketReceiptStatus.RECEIVED
                : TicketReceiptStatus.WAITING;

        return new TicketReceipt(
                ticket.ticketIdAsString(),
                TicketRequestType.ADMISSION,
                receiptStatus,
                ticket.getTicketIssuedAt(),
                STATUS_URL_PREFIX + ticket.ticketIdAsString()
        );
    }

    /**
     * GET /tickets/{ticketId}
     * queuePosition = ZRANK + 1 (WAITING일 때만)
     */
    @Transactional(readOnly = true)
    public Ticket getTicket(String ticketId) {
        Long id = parseTicketId(ticketId);
        WaitingTicket ticket = waitingTicketRepository.findById(id)
                .orElseThrow(() -> new EnrollmentException(ErrorCode.E02, "Ticket not found"));

        if (ticket.getStatus() == TicketStatus.WAITING) {
            Integer rank = waitingQueue().rank(ticket.ticketIdAsString());
            Integer queuePosition = rank == null ? null : rank + 1;
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

    public boolean isActive(String userId) {
        return activeUsers().contains(userId);
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
     * 1초 스케줄: ZPOPMIN 상위 N개 → active_users(TTL) + 티켓/세션 입장 반영.
     */
    @Transactional
    public int admitWaitingUsers() {
        Collection<String> popped = waitingQueue().pollFirst(properties.getAdmitBatchSize());
        if (popped == null || popped.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        int count = 0;
        for (String ticketIdStr : popped) {
            Long ticketId = Long.valueOf(ticketIdStr);
            WaitingTicket ticket = waitingTicketRepository.findById(ticketId).orElse(null);
            if (ticket == null || !ticket.isIncomplete()) {
                continue;
            }

            if (ticket.getRequestType() == TicketRequestType.ADMISSION) {
                activeUsers().add(ticket.getRequesterUserId(), properties.getActiveTtlMinutes(), TimeUnit.MINUTES);
                if (ticket.getSessionRef() != null) {
                    accessSessionRepository.findById(ticket.getSessionRef())
                            .ifPresent(session -> session.admit(now));
                }
                ticket.succeed();
                count++;
            } else {
                // ENROLLMENT/CANCELLATION 은 처리 슬롯으로 PROCESSING 전환 (실행은 Enrollment 쪽)
                ticket.markProcessing();
                count++;
            }
            waitingTicketRepository.save(ticket);
        }
        return count;
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
