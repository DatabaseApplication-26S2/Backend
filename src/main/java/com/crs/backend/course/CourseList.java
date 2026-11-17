package com.crs.backend.course;

import java.util.List;

/**
 * 페이지 없는 과목 목록(openapi.yaml components.schemas.CourseList).
 * 교수 담당 과목 전체를 과목코드 ASCII 오름차순으로 담는다.
 */
public record CourseList(List<CourseView> items) {
}
