package com.crs.backend.application.handler;

import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.port.EnrollmentWriter;
import com.crs.backend.application.port.TicketHandler;
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
public class CancellationTicketHandler implements TicketHandler {

    private static final EnumSet<TicketStatus> INCOMPLETE = EnumSet.of(
            TicketStatus.RECEIVED, TicketStatus.WAITING, TicketStatus.PROCESSING
    );

    private final WaitingTicketJpaRepository waitingTicketRepository;
    private final EnrollmentWriter enrollmentWriter;
    private final RedisWaitingOrderCopy waitingOrderCopy;
    private final JdbcTemplate jdbc;

    public CancellationTicketHandler(
            WaitingTicketJpaRepository waitingTicketRepository,
            EnrollmentWriter enrollmentWriter,
            RedisWaitingOrderCopy waitingOrderCopy,
            JdbcTemplate jdbc
    ) {
        this.waitingTicketRepository = waitingTicketRepository;
        this.enrollmentWriter = enrollmentWriter;
        this.waitingOrderCopy = waitingOrderCopy;
        this.jdbc = jdbc;
    }

    @Override
    public TicketRequestType supports() {
        return TicketRequestType.CANCELLATION;
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

        TicketResultCode code = enrollmentWriter.cancelStudent(
                ticket.getRequesterUserId(),
                ticket.getTargetCourseInstId(),
                ticket.getTicketId()
        );
        // 종결 시각(구현계획 4절 (5)). 판정 뒤 DB 시계에서 읽어 처리 시작 시각과 같은 시계를 쓴다
        Instant finishedAt = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        if (code == TicketResultCode.OK) {
            ticket.succeedIfGeneration(expectedGeneration, finishedAt);
        } else {
            ticket.rejectIfGeneration(expectedGeneration, code, finishedAt);
        }
        waitingTicketRepository.saveAndFlush(ticket);
    }
}
