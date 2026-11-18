package com.crs.backend.application.port;

import com.crs.backend.domain.entity.WaitingTicket;
import com.crs.backend.domain.enums.TicketRequestType;

/**
 * 유형별 티켓 실행 핸들러 (QUE-02 / BE-04).
 */
public interface TicketHandler {

    TicketRequestType supports();

    void handle(WaitingTicket ticket, int expectedGeneration);
}
