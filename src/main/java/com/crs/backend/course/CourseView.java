package com.crs.backend.course;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 과목 응답 1건(openapi.yaml components.schemas.Course).
 * DB 컬럼 이름이 아니라 계약서의 JSON 필드 이름을 쓴다.
 *
 * enrolled는 학생이 호출할 때만 넣는다. 교수·관리자 응답에는 필드 자체가 없어야 하므로
 * null이면 JSON에서 뺀다.
 */
public record CourseView(
        String courseCode,
        String name,                    // courses.course_title
        String category,                // courses.course_category (GENERAL | MAJOR)
        String professorName,           // users.user_name (담당교수 이름)
        String evaluationType,          // courses.grading_type (RELATIVE | ABSOLUTE | PASS_FAIL)
        int capacity,                   // courses.max_capacity
        int minimumEnrollment,          // courses.min_enrollment
        int activeEnrollmentCount,      // courses.active_cnt
        int remainingSeats,             // max(capacity - active, 0)
        String classroom,
        String status,                  // courses.course_status (OPEN | CLOSED)
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean enrolled
) {
}
