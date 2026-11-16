package com.crs.backend.adapter.in.web.dto;

import com.crs.backend.domain.enums.TicketRequestType;
import com.crs.backend.domain.enums.TicketStatus;

import java.time.Instant;

public record Ticket(
        String ticketId,
        TicketRequestType requestType,
        String courseCode,
        Instant submittedAt,
        TicketStatus status,
        Integer queuePosition,
        TicketResult result
) {
}
