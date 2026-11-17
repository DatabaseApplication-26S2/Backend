package com.crs.backend.admin;

/**
 * 관리자 배정 결과(openapi.yaml AdminEnrollmentResult).
 * overCapacity = 배정 직후 현재 인원 > 정원
 */
public record AdminEnrollmentResult(
        String studentId,
        String courseCode,
        String enrollmentType,      // 항상 ADMIN
        String status,              // 항상 ENROLLED
        String enrolledAt,          // 배정 시각(UTC, ...Z)
        int activeEnrollmentCount,  // 배정 직후 현재 인원
        int capacity,
        boolean overCapacity
) {
}
