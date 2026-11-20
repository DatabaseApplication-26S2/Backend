package com.crs.backend.application.handler;

import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.port.CourseLock;
import com.crs.backend.application.port.EnrollmentWriter;
import com.crs.backend.application.port.TicketHandler;
import com.crs.backend.auth.AuthFailure;
import com.crs.backend.auth.SessionGuard;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketRequestType;
import com.crs.backend.domain.enums.TicketResultCode;
import com.crs.backend.domain.enums.TicketStatus;
import com.crs.backend.queue.RedisWaitingOrderCopy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;

@Component
public class EnrollmentTicketHandler implements TicketHandler {

    private static final EnumSet<TicketStatus> INCOMPLETE = EnumSet.of(
            TicketStatus.RECEIVED, TicketStatus.WAITING, TicketStatus.PROCESSING
    );

    private final WaitingTicketJpaRepository waitingTicketRepository;
    private final EnrollmentWriter enrollmentWriter;
    private final CourseLock courseLock;
    private final SessionGuard sessionGuard;
    private final RedisWaitingOrderCopy waitingOrderCopy;
    private final JdbcTemplate jdbc;

    public EnrollmentTicketHandler(
            WaitingTicketJpaRepository waitingTicketRepository,
            EnrollmentWriter enrollmentWriter,
            CourseLock courseLock,
            SessionGuard sessionGuard,
            RedisWaitingOrderCopy waitingOrderCopy,
            JdbcTemplate jdbc
    ) {
        this.waitingTicketRepository = waitingTicketRepository;
        this.enrollmentWriter = enrollmentWriter;
        this.courseLock = courseLock;
        this.sessionGuard = sessionGuard;
        this.waitingOrderCopy = waitingOrderCopy;
        this.jdbc = jdbc;
    }

    @Override
    public TicketRequestType supports() {
        return TicketRequestType.ENROLLMENT;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(WaitingTicket snapshot, int expectedGeneration) {
        WaitingTicket ticket = waitingTicketRepository.findByIdForUpdate(snapshot.getTicketId()).orElse(null);
        if (ticket == null
                || ticket.getGenerationNo() != expectedGeneration
                || ticket.getStatus() != TicketStatus.PROCESSING) {
            return;
        }

        if (ticket.getTargetCourseInstId() != null
                && waitingTicketRepository.existsEarlierIncompleteForCourse(
                ticket.getTargetCourseInstId(), ticket.getTicketId(), INCOMPLETE)) {
            ticket.returnToWaiting();
            waitingTicketRepository.saveAndFlush(ticket);
            waitingOrderCopy.publishAfterCommit(ticket.getTicketId());
            return;
        }

        // 과목 행 잠금(구현계획 4절 (4) 3번). 잠금 순서는 4절 (5)의 티켓 행 → 과목 행 → 세션 행 → 내역이다.
        // 과목이 없어도 계속한다. E04는 writer가 같은 트랜잭션에서 과목을 다시 잠그며(이미 쥔 잠금) 판정한다
        courseLock.lockByInstId(ticket.getTargetCourseInstId());
        TicketResultCode code = sessionRejection(ticket);
        if (code == null) {
            code = enrollmentWriter.enrollStudent(
                    ticket.getRequesterUserId(),
                    ticket.getTargetCourseInstId(),
                    ticket.getTicketId()
            );
        }
        // 종결 시각(구현계획 4절 (5)). 판정 뒤 DB 시계에서 읽어 처리 시작 시각과 같은 시계를 쓴다
        Instant finishedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        if (code == TicketResultCode.OK) {
            ticket.succeedIfGeneration(expectedGeneration, finishedAt);
        } else {
            ticket.rejectIfGeneration(expectedGeneration, code, finishedAt);
        }
        waitingTicketRepository.saveAndFlush(ticket);
    }

    /**
     * 실행 시점 세션 재인가(구현계획 4절 (4) 3번, 판정 순서는 4절 (5)의 E02 → E15 → E04 …).
     * 과목 행을 잠근 뒤 세션 행을 잠근다(4절 (5) 잠금 순서). 대기 중 로그아웃·만료·역할 변경은 E02,
     * 입장 기록({@code entered_at})이 없으면 E15를 돌려주고, 통과하면 {@code null}이다.
     * {@code requireStudent}는 프록시 밖 스냅숏에서 부르므로 그 {@link AuthFailure}가
     * 이 REQUIRES_NEW 트랜잭션을 rollback-only로 만들지 않고, 거절 종결이 커밋된다.
     */
    private TicketResultCode sessionRejection(WaitingTicket ticket) {
        SessionGuard.Snapshot session = sessionGuard.lock(ticket.getSessionRef());
        try {
            session.requireStudent(ticket.getRequesterUserId());
        } catch (AuthFailure e) {
            return TicketResultCode.E02;
        }
        return session.admittedAt() == null ? TicketResultCode.E15 : null;
    }
}
