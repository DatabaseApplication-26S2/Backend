package com.crs.backend.course;

import com.crs.backend.common.ApiProblem.Source;
import com.crs.backend.common.ApiProblem.Violation;
import com.crs.backend.common.CrsException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 과목 입력값 검사(400 E09). 수정(BE-09)과 등록(BE-12)이 함께 쓴다.
 * 위반 메시지에는 입력 값을 되풀이하지 않는다.
 */
public final class CourseInput {

    public static final Pattern COURSE_CODE = Pattern.compile("^[A-Za-z0-9]{1,10}$");
    private static final Set<String> CATEGORIES = Set.of("GENERAL", "MAJOR");
    private static final Set<String> EVALUATION_TYPES = Set.of("RELATIVE", "ABSOLUTE", "PASS_FAIL");

    private CourseInput() {
    }

    /** 경로의 과목코드 형식 */
    public static void requirePathCourseCode(String courseCode) {
        if (courseCode == null || !COURSE_CODE.matcher(courseCode).matches()) {
            throw CrsException.invalid(Source.PATH, "courseCode", "영문자·숫자 1~10자여야 합니다");
        }
    }

    /** 등록: 과목코드 + 6개 필드. 위반이 있으면 E09로 던진다 */
    public static void requireValidCreate(CourseCreateRequest req) {
        List<Violation> v = new ArrayList<>();
        if (req.courseCode() == null) {
            v.add(new Violation(Source.BODY, "/courseCode", "필수 값입니다"));
        } else if (!COURSE_CODE.matcher(req.courseCode()).matches()) {
            v.add(new Violation(Source.BODY, "/courseCode", "영문자·숫자 1~10자여야 합니다"));
        }
        addFieldViolations(v, req.name(), req.category(), req.evaluationType(),
                req.capacity(), req.minimumEnrollment(), req.classroom());
        if (!v.isEmpty()) {
            throw CrsException.invalid(v);
        }
    }

    /** 수정: 수정 가능한 6개 필드. 위반이 있으면 E09로 던진다 */
    public static void requireValidFields(String name, String category, String evaluationType,
                                          Integer capacity, Integer minimumEnrollment, String classroom) {
        List<Violation> v = new ArrayList<>();
        addFieldViolations(v, name, category, evaluationType, capacity, minimumEnrollment, classroom);
        if (!v.isEmpty()) {
            throw CrsException.invalid(v);
        }
    }

    private static void addFieldViolations(List<Violation> v, String name, String category, String evaluationType,
                                           Integer capacity, Integer minimumEnrollment, String classroom) {
        text(v, "/name", name);
        oneOf(v, "/category", category, CATEGORIES);
        oneOf(v, "/evaluationType", evaluationType, EVALUATION_TYPES);
        positive(v, "/capacity", capacity);
        positive(v, "/minimumEnrollment", minimumEnrollment);
        if (capacity != null && minimumEnrollment != null && capacity >= 1 && minimumEnrollment > capacity) {
            v.add(new Violation(Source.BODY, "/minimumEnrollment", "정원보다 클 수 없습니다"));
        }
        text(v, "/classroom", classroom);
    }

    /** 과목명·강의실: courses CHECK (~ '\S')와 같게 공백만 있는 값은 거절한다(text라 길이 제한은 없음) */
    private static void text(List<Violation> v, String field, String value) {
        if (value == null) {
            v.add(new Violation(Source.BODY, field, "필수 값입니다"));
        } else if (value.isBlank()) {
            v.add(new Violation(Source.BODY, field, "비어 있을 수 없습니다"));
        }
    }

    private static void oneOf(List<Violation> v, String field, String value, Set<String> allowed) {
        if (value == null) {
            v.add(new Violation(Source.BODY, field, "필수 값입니다"));
        } else if (!allowed.contains(value)) {
            v.add(new Violation(Source.BODY, field, "허용되지 않는 값입니다"));
        }
    }

    private static void positive(List<Violation> v, String field, Integer value) {
        if (value == null) {
            v.add(new Violation(Source.BODY, field, "필수 값입니다"));
        } else if (value < 1) {
            v.add(new Violation(Source.BODY, field, "1 이상이어야 합니다"));
        }
    }
}
