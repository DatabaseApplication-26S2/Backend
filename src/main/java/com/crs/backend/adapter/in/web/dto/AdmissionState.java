package com.crs.backend.adapter.in.web.dto;

import java.time.Instant;

/**
 * 이미 입장한 세션의 입장 상태 (200).
 */
public record AdmissionState(
        Instant admittedAt
) {
}
