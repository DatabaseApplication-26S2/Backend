package com.crs.backend.course;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 과목 조회 전용 SQL(읽기만 한다).
 * 담당교수 이름(users)과 학생의 신청 여부(enrollments)를 한 번에 가져오려고 직접 SQL을 쓴다.
 */
@Repository
public class CourseQueryRepository {

    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijklmnopqrstuvwxyz";

    /** 공통 SELECT. :studentId가 있으면 enrolled를 계산하고, 없으면 NULL(응답에서 빠짐) */
    private static final String SELECT = """
            SELECT c.course_code, c.course_title, c.course_category, u.user_name AS professor_name,
                   c.grading_type, c.max_capacity, c.min_enrollment, c.active_cnt,
                   c.classroom, c.course_status,
                   %s AS enrolled
              FROM courses c
              JOIN users u ON u.user_id = c.professor_id
            """;

    private static final String ENROLLED_EXPR = """
            EXISTS (SELECT 1 FROM enrollments e
                     WHERE e.course_inst_id = c.course_inst_id
                       AND e.student_id = :studentId
                       AND e.enroll_status = 'ENROLLED')""";

    private final JdbcClient jdbc;

    public CourseQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 검색 결과 한 페이지. category·q가 null이면 그 조건을 두지 않는다 */
    public List<CourseView> search(String category, String q, int page, String studentId) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = select(studentId, params) + where(category, q, params)
                + " ORDER BY c.course_code COLLATE \"C\" LIMIT :limit OFFSET :offset";
        params.put("limit", CoursePage.PAGE_SIZE);
        params.put("offset", (long) (page - 1) * CoursePage.PAGE_SIZE);
        return jdbc.sql(sql).params(params).query(ROW).list();
    }

    /** 검색 조건에 맞는 전체 건수 */
    public long count(String category, String q) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = "SELECT count(*) FROM courses c JOIN users u ON u.user_id = c.professor_id"
                + where(category, q, params);
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    /** 교수 담당 과목 전체(폐강 포함). enrolled는 넣지 않는다 */
    public List<CourseView> findByProfessor(String professorId) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = select(null, params)
                + " WHERE c.professor_id = :professorId ORDER BY c.course_code COLLATE \"C\"";
        params.put("professorId", professorId);
        return jdbc.sql(sql).params(params).query(ROW).list();
    }

    /**
     * 학생의 현재 신청(ENROLLED) 과목. 폐강으로 CLOSED가 된 행은 빠진다.
     */
    public List<MyEnrollmentView> findEnrollments(String studentId) {
        return jdbc.sql("""
                        SELECT c.course_code, c.course_title, c.course_category, u.user_name AS professor_name,
                               c.classroom, e.enroll_type, e.enrolled_at, e.enroll_status
                          FROM enrollments e
                          JOIN courses c ON c.course_inst_id = e.course_inst_id
                          JOIN users u ON u.user_id = c.professor_id
                         WHERE e.student_id = :studentId
                           AND e.enroll_status = 'ENROLLED'
                         ORDER BY c.course_code COLLATE "C"
                        """)
                .param("studentId", studentId)
                .query((rs, i) -> new MyEnrollmentView(
                        rs.getString("course_code"),
                        rs.getString("course_title"),
                        rs.getString("course_category"),
                        rs.getString("professor_name"),
                        rs.getString("classroom"),
                        rs.getString("enroll_type"),
                        rs.getObject("enrolled_at", OffsetDateTime.class).toInstant().toString(), // ...Z
                        rs.getString("enroll_status")))
                .list();
    }

    private String select(String studentId, Map<String, Object> params) {
        if (studentId == null) {
            return SELECT.formatted("CAST(NULL AS boolean)");
        }
        params.put("studentId", studentId);
        return SELECT.formatted(ENROLLED_EXPR);
    }

    /**
     * 검색어 규칙(openapi.yaml SearchQuery):
     * 과목명·과목코드·교수 이름 중 하나라도 q를 포함하면 일치. ASCII 영문자만 대소문자 무시.
     * %, _ 를 글자 그대로 찾으려고 LIKE 대신 strpos + translate를 쓴다.
     */
    private String where(String category, String q, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        if (category != null) {
            conditions.add("c.course_category = :category");
            params.put("category", category);
        }
        if (q != null && !q.isEmpty()) {
            conditions.add("""
                    (strpos(translate(c.course_title, :upper, :lower), translate(:q, :upper, :lower)) > 0
                     OR strpos(translate(c.course_code, :upper, :lower), translate(:q, :upper, :lower)) > 0
                     OR strpos(translate(u.user_name, :upper, :lower), translate(:q, :upper, :lower)) > 0)""");
            params.put("q", q);
            params.put("upper", UPPER);
            params.put("lower", LOWER);
        }
        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private static final RowMapper<CourseView> ROW = (rs, i) -> {
        int capacity = rs.getInt("max_capacity");
        int active = rs.getInt("active_cnt");
        boolean enrolled = rs.getBoolean("enrolled");
        Boolean enrolledOrNull = rs.wasNull() ? null : enrolled;
        return new CourseView(
                rs.getString("course_code"),
                rs.getString("course_title"),
                rs.getString("course_category"),
                rs.getString("professor_name"),
                rs.getString("grading_type"),
                capacity,
                rs.getInt("min_enrollment"),
                active,
                Math.max(capacity - active, 0),
                rs.getString("classroom"),
                rs.getString("course_status"),
                enrolledOrNull);
    };
}
