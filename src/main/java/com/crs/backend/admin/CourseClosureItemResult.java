package com.crs.backend.admin;

/**
 * 일괄 폐강의 과목 1건 결과(openapi.yaml CourseClosureItemResult).
 * code: OK | E02 | E04 | E14. closedEnrollmentCount는 OK일 때만 숫자, 그 밖에는 null.
 */
public record CourseClosureItemResult(String courseCode, String code, Integer closedEnrollmentCount) {

    public static CourseClosureItemResult ok(String courseCode, int closed) {
        return new CourseClosureItemResult(courseCode, "OK", closed);
    }

    public static CourseClosureItemResult failed(String courseCode, String code) {
        return new CourseClosureItemResult(courseCode, code, null);
    }
}
