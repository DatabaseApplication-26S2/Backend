package com.crs.backend.course;

import java.util.List;

/** 폐강 대상 페이지(openapi.yaml ClosureCandidatePage). 20건 고정, 과목코드 ASCII 오름차순 */
public record ClosureCandidatePage(List<ClosureCandidate> items, int page, int pageSize, long totalItems,
                                   long totalPages) {

    public static ClosureCandidatePage of(List<ClosureCandidate> items, int page, long totalItems) {
        long totalPages = (totalItems + CoursePage.PAGE_SIZE - 1) / CoursePage.PAGE_SIZE;
        return new ClosureCandidatePage(items, page, CoursePage.PAGE_SIZE, totalItems, totalPages);
    }
}
