package com.crs.backend.course;

/**
 * 담당 과목 폐강 결과(openapi.yaml CourseClosureResult).
 * course는 폐강 후 과목(status CLOSED, activeEnrollmentCount 0),
 * closedEnrollmentCount는 이 폐강으로 ENROLLED에서 CLOSED로 바뀌어 보존된 행 수다.
 */
public record CourseClosureResult(CourseView course, int closedEnrollmentCount) {
}
