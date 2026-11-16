package com.crs.backend.adapter.in.web.dto;

import com.crs.backend.domain.enums.TicketResultCode;

import java.time.Instant;

public record TicketResult(
        TicketResultCode code,
        Instant completedAt
) {
}
