package com.crs.backend.application.handler;

import com.crs.backend.adapter.out.persistence.jpa.WaitingTicketJpaRepository;
import com.crs.backend.application.port.TicketHandler;
import com.crs.backend.auth.AuthService;
import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketRequestType;
import com.crs.backend.domain.enums.TicketStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LOGIN 워커 — 김재섭 AuthService.processLogin 을 그대로 호출한다.
 */
@Component
public class LoginTicketHandler implements TicketHandler {

    private final WaitingTicketJpaRepository waitingTicketRepository;
    private final AuthService authService;

    public LoginTicketHandler(WaitingTicketJpaRepository waitingTicketRepository, AuthService authService) {
        this.waitingTicketRepository = waitingTicketRepository;
        this.authService = authService;
    }

    @Override
    public TicketRequestType supports() {
        return TicketRequestType.LOGIN;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(WaitingTicket snapshot, int expectedGeneration) {
        WaitingTicket ticket = waitingTicketRepository.findById(snapshot.getTicketId()).orElse(null);
        if (ticket == null
                || ticket.getGenerationNo() != expectedGeneration
                || ticket.getStatus() != TicketStatus.PROCESSING) {
            return;
        }
        authService.processLogin(ticket);
        waitingTicketRepository.save(ticket);
    }
}
