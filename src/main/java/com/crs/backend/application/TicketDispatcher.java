package com.crs.backend.application;

import com.crs.backend.adapter.out.persistence.jpa.ServiceSettingsRepository;
import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.port.TicketHandler;
import com.crs.backend.domain.entity.ServiceSettings;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketRequestType;
import com.crs.backend.domain.enums.TicketStatus;
import com.crs.backend.queue.RedisWaitingOrderCopy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * QUE-02-01 DB claim (N′ = max(N − PROCESSING, 0)) 후 유형별 핸들러 동기 실행.
 */
@Service
public class TicketDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TicketDispatcher.class);
    private static final int LEASE_SECONDS = 30;

    private final ServiceSettingsRepository serviceSettingsRepository;
    private final WaitingTicketJpaRepository waitingTicketRepository;
    private final RedisWaitingOrderCopy waitingOrderCopy;
    private final Map<TicketRequestType, TicketHandler> handlers;
    private final TransactionTemplate claimTx;
    private final String workerId;

    public TicketDispatcher(
            ServiceSettingsRepository serviceSettingsRepository,
            WaitingTicketJpaRepository waitingTicketRepository,
            RedisWaitingOrderCopy waitingOrderCopy,
            List<TicketHandler> handlerList,
            PlatformTransactionManager transactionManager
    ) {
        this.serviceSettingsRepository = serviceSettingsRepository;
        this.waitingTicketRepository = waitingTicketRepository;
        this.waitingOrderCopy = waitingOrderCopy;
        this.handlers = new EnumMap<>(TicketRequestType.class);
        for (TicketHandler handler : handlerList) {
            this.handlers.put(handler.supports(), handler);
        }
        this.claimTx = new TransactionTemplate(transactionManager);
        this.workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public int claimAndDispatch() {
        List<ClaimedWork> works = claimTx.execute(status -> claimBatch());
        if (works == null || works.isEmpty()) {
            return 0;
        }
        for (ClaimedWork work : works) {
            TicketHandler handler = handlers.get(work.requestType());
            if (handler == null) {
                log.warn("No TicketHandler for {}", work.requestType());
                continue;
            }
            try {
                WaitingTicket ticket = waitingTicketRepository.findById(work.ticketId()).orElse(null);
                if (ticket != null) {
                    handler.handle(ticket, work.generation());
                }
            } catch (Exception e) {
                log.error("Ticket handler failed ticketId={}", work.ticketId(), e);
            }
        }
        return works.size();
    }

    private List<ClaimedWork> claimBatch() {
        ServiceSettings settings = serviceSettingsRepository
                .findByIdForUpdate(ServiceSettings.SINGLETON_ID)
                .orElseGet(() -> serviceSettingsRepository.save(ServiceSettings.initial()));

        long processing = waitingTicketRepository.countByStatus(TicketStatus.PROCESSING);
        int nPrime = Math.max(settings.getConcurrencyLimit() - (int) processing, 0);
        if (nPrime <= 0) {
            return List.of();
        }

        List<WaitingTicket> claimed = waitingTicketRepository.findByStatusForClaim(
                TicketStatus.WAITING, PageRequest.of(0, nPrime)
        );
        if (claimed.isEmpty()) {
            return List.of();
        }

        Instant leaseUntil = Instant.now().plus(LEASE_SECONDS, ChronoUnit.SECONDS);
        for (WaitingTicket ticket : claimed) {
            ticket.claim(workerId, String.valueOf(ticket.getTicketId()), leaseUntil);
            waitingTicketRepository.save(ticket);
            waitingOrderCopy.removeAfterCommit(ticket.getTicketId());
        }

        return claimed.stream()
                .map(t -> new ClaimedWork(t.getTicketId(), t.getGenerationNo(), t.getRequestType()))
                .toList();
    }

    private record ClaimedWork(long ticketId, int generation, TicketRequestType requestType) {
    }
}
