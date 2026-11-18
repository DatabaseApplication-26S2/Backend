package com.crs.backend.admin;

/** 관리자 배정 본문(openapi.yaml AdminEnrollmentRequest). 정의되지 않은 필드는 400 E09 */
public record AdminEnrollmentRequest(String studentId, String courseCode) {
}
