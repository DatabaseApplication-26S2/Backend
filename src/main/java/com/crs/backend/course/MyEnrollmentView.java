package com.crs.backend.course;

/**
 * 학생 본인의 현재 신청 과목 1건(openapi.yaml components.schemas.MyEnrollment).
 * enrolledAt은 UTC RFC 3339 문자열(예: 2026-11-10T00:01:03Z)이다.
 */
public record MyEnrollmentView(
        String courseCode,
        String name,            // courses.course_title
        String category,        // courses.course_category
        String professorName,   // users.user_name
        String classroom,
        String enrollmentType,  // enrollments.enroll_type (STUDENT | ADMIN)
        String enrolledAt,      // enrollments.enrolled_at
        String status           // 항상 ENROLLED
) {
}
