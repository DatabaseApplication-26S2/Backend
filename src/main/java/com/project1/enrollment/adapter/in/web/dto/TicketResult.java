package com.project1.enrollment.adapter.in.web.dto;

import com.project1.enrollment.domain.enums.TicketResultCode;

import java.time.Instant;

public record TicketResult(
        TicketResultCode code,
        Instant completedAt
) {
}
