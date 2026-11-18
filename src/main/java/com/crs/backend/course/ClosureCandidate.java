package com.crs.backend.course;

/** 폐강 대상 과목 1건(openapi.yaml ClosureCandidate): OPEN이고 현재 인원 < 최소 수강인원 */
public record ClosureCandidate(
        String courseCode,
        String name,
        String professorName,
        int activeEnrollmentCount,
        int minimumEnrollment,
        int capacity
) {
}
