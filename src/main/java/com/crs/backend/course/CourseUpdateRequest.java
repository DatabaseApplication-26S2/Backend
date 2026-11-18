package com.crs.backend.course;

/**
 * 교수 과목 수정 본문(openapi.yaml CourseUpdateRequest). 수정 가능한 6개 필드 전체를 보낸다.
 * courseCode·담당교수·인원·상태 같은 다른 필드는 정의되지 않은 필드라 400 E09가 된다(BE-01 설정).
 * 빠진 필드를 알아내려고 숫자도 Integer(null 허용)로 받는다.
 */
public record CourseUpdateRequest(
        String name,
        String category,
        String evaluationType,
        Integer capacity,
        Integer minimumEnrollment,
        String classroom
) {
}
