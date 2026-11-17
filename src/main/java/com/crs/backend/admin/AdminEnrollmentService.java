package com.crs.backend.admin;

import com.crs.backend.adapter.out.persistence.jpa.CourseJpaRepository;
import com.crs.backend.adapter.out.persistence.jpa.UserAccountRepository;
import com.crs.backend.auth.CurrentUser;
import com.crs.backend.common.ApiProblem.Source;
import com.crs.backend.common.ApiProblem.Violation;
import com.crs.backend.common.CrsException;
import com.crs.backend.course.CourseInput;
import com.crs.backend.course.CourseWriteService;
import com.crs.backend.domain.entity.Course;
import com.crs.backend.domain.entity.UserAccount;
import com.crs.backend.domain.enums.UserRole;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * ADM-01-01 관리자 수강 배정. 대기열·N 슬롯을 거치지 않고, 같은 과목 잠금만 쓴다.
 *
 * 판정 순서(openapi.yaml createAdminEnrollment):
 * Access(401) → ADMIN(403) → 본문(400 E09) → studentId 확인(422 E13) → 과목 없음(404 E04)
 * → [과목 잠금] → 폐강(409 E05) → 이미 신청(409 E06) → 201
 */
@Service
public class AdminEnrollmentService {

    private final UserAccountRepository users;
    private final CourseJpaRepository courses;
    private final CourseWriteService writer;

    public AdminEnrollmentService(UserAccountRepository users, CourseJpaRepository courses, CourseWriteService writer) {
        this.users = users;
        this.courses = courses;
        this.writer = writer;
    }

    public AdminEnrollmentResult assign(CurrentUser admin, AdminEnrollmentRequest req) {
        if (admin.role() != UserRole.ADMIN) {
            throw CrsException.forbidden(); // 보안 필터도 막지만 한 번 더 확인
        }
        validate(req);

        // 계정은 시드 전용이고 바뀌지 않으므로 잠금 없이 읽어도 된다
        boolean student = users.findById(req.studentId()).map(UserAccount::isStudent).orElse(false);
        if (!student) {
            throw CrsException.of(HttpStatus.valueOf(422), "E13", "학생 계정이 아닙니다");
        }

        String courseInstId = courses.findByCourseCode(req.courseCode())
                .map(Course::getCourseInstId)
                .orElseThrow(() -> CrsException.of(HttpStatus.NOT_FOUND, "E04", "과목을 찾을 수 없습니다"));

        return writer.assignByAdmin(courseInstId, req.courseCode(), req.studentId(), admin.userId());
    }

    private static void validate(AdminEnrollmentRequest req) {
        List<Violation> v = new ArrayList<>();
        if (req.studentId() == null || req.studentId().isEmpty()) {
            v.add(new Violation(Source.BODY, "/studentId", "필수 값입니다"));
        }
        if (req.courseCode() == null) {
            v.add(new Violation(Source.BODY, "/courseCode", "필수 값입니다"));
        } else if (!CourseInput.COURSE_CODE.matcher(req.courseCode()).matches()) {
            v.add(new Violation(Source.BODY, "/courseCode", "영문자·숫자 1~10자여야 합니다"));
        }
        if (!v.isEmpty()) {
            throw CrsException.invalid(v);
        }
    }
}
