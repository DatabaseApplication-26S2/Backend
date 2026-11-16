package com.project1.enrollment.adapter.in.web.dto;

/**
 * 수강 신청 본문. 학생ID는 Access 주체(현재는 X-User-Id)에서만 가져온다.
 */
public record EnrollmentRequest(
        String courseCode
) {
}
