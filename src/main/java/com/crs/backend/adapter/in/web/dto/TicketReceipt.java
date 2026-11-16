package com.crs.backend.adapter.in.web.dto;

import com.crs.backend.domain.enums.TicketRequestType;

import java.time.Instant;

public record TicketReceipt(
        String ticketId,
        TicketRequestType requestType,
        TicketReceiptStatus status,
        Instant submittedAt,
        String statusUrl
) {
}
