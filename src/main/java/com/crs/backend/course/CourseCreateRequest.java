package com.crs.backend.course;

/**
 * 과목 등록 본문(openapi.yaml CourseCreateRequest).
 * 담당교수는 로그인한 교수로 정해지며 본문으로 받지 않는다(professorId 등을 보내면 정의되지 않은 필드라 E09).
 */
public record CourseCreateRequest(
        String courseCode,
        String name,
        String category,
        String evaluationType,
        Integer capacity,
        Integer minimumEnrollment,
        String classroom
) {
}
