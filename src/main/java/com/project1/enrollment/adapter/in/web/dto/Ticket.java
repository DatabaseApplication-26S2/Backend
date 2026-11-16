package com.project1.enrollment.adapter.in.web.dto;

import com.project1.enrollment.domain.enums.TicketRequestType;
import com.project1.enrollment.domain.enums.TicketStatus;

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
