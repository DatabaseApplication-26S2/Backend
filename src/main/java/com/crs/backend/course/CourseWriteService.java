package com.crs.backend.course;

import com.crs.backend.admin.AdminEnrollmentResult;
import com.crs.backend.common.CrsException;
import com.crs.backend.lock.DistributedLock;
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
 * 잠금 순서는 학생 수강신청(EnrollmentService)과 같다.
 *   과목 단위 분산 락(lock:course:{id}) → 새 트랜잭션 → 과목 행 잠금 → 재검증 → 쓰기 → 커밋 → 락 해제
 *
 * 규칙(구현계획 1.4 혼용 규칙 6):
 *   잠금 전에 읽은 과목 엔티티로 판단하거나 쓰지 않는다. 잠금이 돌려준 현재 값으로만 판단하고,
 *   바꿀 컬럼만 직접 UPDATE한다. 그래야 그사이 커밋된 인원(active_cnt)·상태를 덮어쓰지 않는다.
 */
@Service
public class CourseWriteService {

    /** 잠금 아래에서 읽은 현재 값 */
    record LockedCourse(String professorId, String status, int capacity, int active) {
    }

    private final JdbcClient jdbc;

    public CourseWriteService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * ADM-01-01 관리자 배정. 정원 검사만 생략한다(정원을 넘을 수 있음).
     * 판정: 과목 잠금 → 과목 없음(E04) → 폐강(E05) → 이미 신청(E06) → 내역 생성 + 인원 +1
     */
    @DistributedLock(key = "#courseInstId", keyPrefix = "lock:course:")
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
    @DistributedLock(key = "#courseInstId", keyPrefix = "lock:course:")
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

    /** 과목 행을 잠그고 현재 값을 읽는다. 없으면 404 E04 */
    private LockedCourse lock(String courseInstId) {
        return jdbc.sql("""
                        SELECT professor_id, course_status, max_capacity, active_cnt
                          FROM courses
                         WHERE course_inst_id = :courseInstId
                           FOR NO KEY UPDATE""")
                .param("courseInstId", courseInstId)
                .query((rs, i) -> new LockedCourse(rs.getString("professor_id"), rs.getString("course_status"),
                        rs.getInt("max_capacity"), rs.getInt("active_cnt")))
                .optional()
                .orElseThrow(() -> CrsException.of(HttpStatus.NOT_FOUND, "E04", "과목을 찾을 수 없습니다"));
    }
}
