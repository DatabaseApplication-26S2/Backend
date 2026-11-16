package com.project1.enrollment.adapter.in.web.dto;

import com.project1.enrollment.domain.enums.TicketRequestType;

import java.time.Instant;

public record TicketReceipt(
        String ticketId,
        TicketRequestType requestType,
        TicketReceiptStatus status,
        Instant submittedAt,
        String statusUrl
) {
}
