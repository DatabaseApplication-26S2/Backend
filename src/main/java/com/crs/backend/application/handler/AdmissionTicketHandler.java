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
 * ADMISSION 워커 — AuthService.processAdmission을 그대로 호출한다.
 * 입장은 현재 세션의 sessions.entered_at으로만 기록한다(SessionGuard 세션 행 잠금·재인가). Redis 입장 상태는 두지 않는다.
 */
@Component
public class AdmissionTicketHandler implements TicketHandler {

    private final WaitingTicketJpaRepository waitingTicketRepository;
    private final AuthService authService;

    public AdmissionTicketHandler(WaitingTicketJpaRepository waitingTicketRepository, AuthService authService) {
        this.waitingTicketRepository = waitingTicketRepository;
        this.authService = authService;
    }

    @Override
    public TicketRequestType supports() {
        return TicketRequestType.ADMISSION;
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
        authService.processAdmission(ticket);
    }
}
