package com.crs.backend.application.handler;

import com.crs.backend.adapter.out.persistence.jpa.AccessSessionRepository;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.port.TicketHandler;
import com.crs.backend.domain.entity.AccessSession;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketRequestType;
import com.crs.backend.domain.enums.TicketResultCode;
import com.crs.backend.domain.enums.TicketStatus;
import com.crs.backend.queue.QueueProperties;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * ADMISSION 워커. sessions.entered_at 기록 + 기존 active_users 호환 유지.
 */
@Component
public class AdmissionTicketHandler implements TicketHandler {

    private final WaitingTicketJpaRepository waitingTicketRepository;
    private final AccessSessionRepository accessSessionRepository;
    private final RedissonClient redissonClient;
    private final QueueProperties properties;

    public AdmissionTicketHandler(
            WaitingTicketJpaRepository waitingTicketRepository,
            AccessSessionRepository accessSessionRepository,
            RedissonClient redissonClient,
            QueueProperties properties
    ) {
        this.waitingTicketRepository = waitingTicketRepository;
        this.accessSessionRepository = accessSessionRepository;
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    @Override
    public TicketRequestType supports() {
        return TicketRequestType.ADMISSION;
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

        Instant now = Instant.now();
        if (ticket.getSessionRef() == null) {
            ticket.rejectIfGeneration(expectedGeneration, TicketResultCode.E02);
            waitingTicketRepository.saveAndFlush(ticket);
            return;
        }

        AccessSession session = accessSessionRepository.findById(ticket.getSessionRef()).orElse(null);
        if (session == null || !session.isValid(now)) {
            ticket.rejectIfGeneration(expectedGeneration, TicketResultCode.E02);
            waitingTicketRepository.saveAndFlush(ticket);
            return;
        }

        session.admit(now);
        accessSessionRepository.save(session);
        redissonClient.getSetCache(properties.getActiveKey())
                .add(ticket.getRequesterUserId(), properties.getActiveTtlMinutes(), TimeUnit.MINUTES);

        ticket.succeedIfGeneration(expectedGeneration);
        waitingTicketRepository.saveAndFlush(ticket);
    }
}
