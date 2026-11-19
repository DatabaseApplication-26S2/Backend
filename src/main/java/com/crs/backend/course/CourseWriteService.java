package com.crs.backend.course;

import com.crs.backend.admin.AdminEnrollmentResult;
import com.crs.backend.common.CrsException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 같은 과목 잠금 아래에서 실행하는 과목 변경(BE-09).
 *
 * 잠금 순서는 학생 수강신청(EnrollmentWriter)과 같다. 같은 과목 행을 DB 행 잠금으로 직렬화한다.
 *   새 트랜잭션 → 과목 행 잠금(FOR NO KEY UPDATE) → 재검증 → 쓰기 → 커밋(잠금 해제)
 *
 * 규칙(구현계획 1.4 혼용 규칙 6):
 *   잠금 전에 읽은 과목 엔티티로 판단하거나 쓰지 않는다. 잠금이 돌려준 현재 값으로만 판단하고,
 *   바꿀 컬럼만 직접 UPDATE한다. 그래야 그사이 커밋된 인원(active_cnt)·상태를 덮어쓰지 않는다.
 */
@Service
public class CourseWriteService {

    /** 잠금 아래에서 읽은 현재 값 */
    record LockedCourse(String professorId, String status, int capacity, int active, int minimum) {
    }

    private final JdbcClient jdbc;

    public CourseWriteService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * ADM-01-01 관리자 배정. 정원 검사만 생략한다(정원을 넘을 수 있음).
     * 판정: 과목 잠금 → 과목 없음(E04) → 폐강(E05) → 이미 신청(E06) → 내역 생성 + 인원 +1
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdminEnrollmentResult assignByAdmin(String courseInstId, String courseCode,
                                               String studentId, String adminId) {
        LockedCourse course = lock(courseInstId);
        if (!"OPEN".equals(course.status())) {
            throw CrsException.of(HttpStatus.CONFLICT, "E05", "폐강된 과목입니다");
        }
        long existing = jdbc.sql("""
                        SELECT count(*) FROM enrollments
                         WHERE student_id = :studentId AND course_inst_id = :courseInstId""")
                .param("studentId", studentId).param("courseInstId", courseInstId)
                .query(Long.class).single();
        if (existing > 0) {
            throw CrsException.of(HttpStatus.CONFLICT, "E06", "이미 신청한 과목입니다");
        }

        Instant now = Instant.now();
        jdbc.sql("""
                        INSERT INTO enrollments (student_id, course_inst_id, enroll_status, enroll_type,
                                                 enrolled_at, assigned_by_user_id, source_ticket_id)
                        VALUES (:studentId, :courseInstId, 'ENROLLED', 'ADMIN', :now, :adminId, NULL)""")
                .param("studentId", studentId).param("courseInstId", courseInstId)
                .param("now", Timestamp.from(now)).param("adminId", adminId)
                .update();
        int active = jdbc.sql("""
                        UPDATE courses SET active_cnt = active_cnt + 1
                         WHERE course_inst_id = :courseInstId
                        RETURNING active_cnt""")
                .param("courseInstId", courseInstId)
                .query(Integer.class).single();

        return new AdminEnrollmentResult(studentId, courseCode, "ADMIN", "ENROLLED", now.toString(),
                active, course.capacity(), active > course.capacity());
    }

    /**
     * CMG-02-02 교수 과목 수정.
     * 판정: 과목 잠금 → 과목 없음(E04) → 담당 교수 아님(403 FORBIDDEN) → 폐강(E05)
     *       → 정원을 바꿨고 새 정원 < 현재 인원(E11) → 6개 컬럼과 수정 시각만 UPDATE
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateByProfessor(String courseInstId, String professorId, CourseUpdateRequest req) {
        LockedCourse course = lock(courseInstId);
        if (!course.professorId().equals(professorId)) {
            throw CrsException.forbidden();
        }
        if (!"OPEN".equals(course.status())) {
            throw CrsException.of(HttpStatus.CONFLICT, "E05", "폐강된 과목은 수정할 수 없습니다");
        }
        if (req.capacity() != course.capacity() && req.capacity() < course.active()) {
            throw CrsException.of(HttpStatus.CONFLICT, "E11", "정원이 현재 인원보다 작습니다");
        }
        // active_cnt·course_status·course_code·professor_id는 SET하지 않는다
        jdbc.sql("""
                        UPDATE courses
                           SET course_title = :name, course_category = :category, grading_type = :evaluationType,
                               max_capacity = :capacity, min_enrollment = :minimumEnrollment,
                               classroom = :classroom, course_updated_at = :now
                         WHERE course_inst_id = :courseInstId""")
                .param("name", req.name()).param("category", req.category())
                .param("evaluationType", req.evaluationType()).param("capacity", req.capacity())
                .param("minimumEnrollment", req.minimumEnrollment()).param("classroom", req.classroom())
                .param("now", Timestamp.from(Instant.now())).param("courseInstId", courseInstId)
                .update();
    }

    /**
     * CMG-02-01 과목 등록. 새 과목은 OPEN, 인원 0이며 새 내부 ID를 받는다.
     * 같은 코드가 동시에 와도 유일성 제약(courses.course_code UNIQUE)으로 하나만 들어간다.
     * ON CONFLICT DO NOTHING으로 예외 대신 "0행"을 받아 409 E10으로 바꾼다(폐강 과목의 코드도 포함).
     */
    @Transactional
    public void create(String professorId, CourseCreateRequest req) {
        boolean inserted = jdbc.sql("""
                        INSERT INTO courses (course_code, professor_id, course_title, course_category, grading_type,
                                             max_capacity, min_enrollment, active_cnt, classroom, course_status,
                                             course_created_at)
                        VALUES (:courseCode, :professorId, :name, :category, :evaluationType,
                                :capacity, :minimumEnrollment, 0, :classroom, 'OPEN', :now)
                        ON CONFLICT (course_code) DO NOTHING
                        RETURNING course_inst_id""")
                .param("courseCode", req.courseCode()).param("professorId", professorId)
                .param("name", req.name()).param("category", req.category())
                .param("evaluationType", req.evaluationType()).param("capacity", req.capacity())
                .param("minimumEnrollment", req.minimumEnrollment()).param("classroom", req.classroom())
                .param("now", Timestamp.from(Instant.now()))
                .query(String.class).optional().isPresent();
        if (!inserted) {
            throw CrsException.of(HttpStatus.CONFLICT, "E10",
                    "과목코드 " + req.courseCode() + "은 이미 사용 중입니다. 폐강된 과목의 코드도 다시 쓸 수 없습니다.");
        }
    }

    /**
     * CMG-02-03 교수 폐강 · ADM-03-02 관리자 폐강(과목 1건).
     * 판정: 과목 잠금 → 과목 없음(E04) → [교수면] 담당 교수 아님(403 FORBIDDEN)
     *       → "OPEN이고 현재 인원 < 최소 수강인원"이 아니면 409 E14(이미 폐강 포함, 멱등 성공 아님)
     * 효과(한 트랜잭션): ENROLLED 내역 전부 CLOSED로 보존, 과목 CLOSED, 인원 0.
     *
     * @param professorId 교수 폐강이면 로그인한 교수 ID, 관리자 폐강이면 null
     * @return CLOSED로 바뀐 내역 수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int close(String courseInstId, String professorId) {
        LockedCourse course = lock(courseInstId);
        if (professorId != null && !course.professorId().equals(professorId)) {
            throw CrsException.forbidden();
        }
        if (!"OPEN".equals(course.status())) {
            throw CrsException.of(HttpStatus.CONFLICT, "E14", "이미 폐강된 과목입니다.");
        }
        if (course.active() >= course.minimum()) {
            throw CrsException.of(HttpStatus.CONFLICT, "E14",
                    "개설 상태이면서 현재 인원이 최소 수강인원보다 적은 과목만 폐강할 수 있습니다.");
        }
        int closedEnrollments = jdbc.sql("""
                        UPDATE enrollments SET enroll_status = 'CLOSED'
                         WHERE course_inst_id = :courseInstId AND enroll_status = 'ENROLLED'""")
                .param("courseInstId", courseInstId)
                .update();
        // 상태와 인원을 한 문장에서 바꿔 CHECK(course_status = 'OPEN' OR active_cnt = 0)를 지킨다
        jdbc.sql("""
                        UPDATE courses
                           SET course_status = 'CLOSED', active_cnt = 0, course_updated_at = :now
                         WHERE course_inst_id = :courseInstId""")
                .param("now", Timestamp.from(Instant.now())).param("courseInstId", courseInstId)
                .update();
        return closedEnrollments;
    }

    /** 과목 행을 잠그고 현재 값을 읽는다. 없으면 404 E04 */
    private LockedCourse lock(String courseInstId) {
        return jdbc.sql("""
                        SELECT professor_id, course_status, max_capacity, active_cnt, min_enrollment
                          FROM courses
                         WHERE course_inst_id = :courseInstId
                           FOR NO KEY UPDATE""")
                .param("courseInstId", courseInstId)
                .query((rs, i) -> new LockedCourse(rs.getString("professor_id"), rs.getString("course_status"),
                        rs.getInt("max_capacity"), rs.getInt("active_cnt"), rs.getInt("min_enrollment")))
                .optional()
                .orElseThrow(() -> CrsException.of(HttpStatus.NOT_FOUND, "E04", "과목을 찾을 수 없습니다"));
    }
}
