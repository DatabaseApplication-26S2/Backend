package com.crs.backend.course;

import com.crs.backend.adapter.out.persistence.jpa.CourseJpaRepository;
import com.crs.backend.auth.CurrentUser;
import com.crs.backend.common.CrsException;
import com.crs.backend.domain.entity.Course;
import com.crs.backend.domain.enums.UserRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 교수 과목 관리(CMG-02). 잠금이 필요한 쓰기는 CourseWriteService에 맡긴다.
 * (같은 클래스 안에서 부르면 락·트랜잭션 프록시가 동작하지 않으므로 클래스를 나눴다)
 */
@Service
public class ProfessorCourseService {

    private final CourseJpaRepository courses;
    private final CourseWriteService writer;
    private final CourseQueryRepository query;

    public ProfessorCourseService(CourseJpaRepository courses, CourseWriteService writer, CourseQueryRepository query) {
        this.courses = courses;
        this.writer = writer;
        this.query = query;
    }

    /**
     * 판정 순서(openapi.yaml updateProfessorCourse):
     * Access(401, 필터) → PROFESSOR(403) → 경로·본문(400 E09) → 과목 잠금 → E04 → 담당 아님(403) → E05 → E11 → 200
     */
    public CourseView update(CurrentUser user, String courseCode, CourseUpdateRequest req) {
        if (user.role() != UserRole.PROFESSOR) {
            throw CrsException.forbidden();
        }
        CourseInput.requirePathCourseCode(courseCode);
        CourseInput.requireValidFields(req.name(), req.category(), req.evaluationType(),
                req.capacity(), req.minimumEnrollment(), req.classroom());

        // 과목은 삭제되지 않고 코드도 바뀌지 않으므로, 코드 → 내부 ID 변환은 잠금 전에 해도 된다
        String courseInstId = courses.findByCourseCode(courseCode)
                .map(Course::getCourseInstId)
                .orElseThrow(() -> CrsException.of(HttpStatus.NOT_FOUND, "E04", "과목을 찾을 수 없습니다"));

        writer.updateByProfessor(courseInstId, user.userId(), req);

        // 커밋된 값으로 응답한다
        return query.findByCode(courseCode).orElseThrow();
    }
}
