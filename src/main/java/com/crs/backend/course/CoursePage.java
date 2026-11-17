package com.crs.backend.course;

import java.util.List;

/**
 * 과목 검색 페이지(openapi.yaml components.schemas.CoursePage).
 * 페이지 크기 20 고정, 과목코드 ASCII 오름차순. 0건이면 totalPages = 0.
 */
public record CoursePage(List<CourseView> items, int page, int pageSize, long totalItems, long totalPages) {

    public static final int PAGE_SIZE = 20;

    public static CoursePage of(List<CourseView> items, int page, long totalItems) {
        long totalPages = (totalItems + PAGE_SIZE - 1) / PAGE_SIZE; // ceil(totalItems / 20)
        return new CoursePage(items, page, PAGE_SIZE, totalItems, totalPages);
    }
}
