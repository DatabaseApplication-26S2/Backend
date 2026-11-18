package com.crs.backend.admin;

import com.crs.backend.adapter.out.persistence.jpa.AccessSessionRepository;
import com.crs.backend.adapter.out.persistence.jpa.CourseJpaRepository;
import com.crs.backend.auth.CurrentUser;
import com.crs.backend.common.ApiProblem.Source;
import com.crs.backend.common.ApiProblem.Violation;
import com.crs.backend.common.CrsException;
import com.crs.backend.course.CourseInput;
import com.crs.backend.course.CourseWriteService;
import com.crs.backend.domain.entity.Course;
import com.crs.backend.domain.enums.UserRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * ADM-03-02 일괄 폐강. 과목마다 별도 트랜잭션으로 처리하고 입력 순서의 결과를 돌려준다(전체 롤백 없음).
 *
 * 요청 단계(실패하면 어떤 과목도 처리하지 않음): Access(401) → ADMIN(403) → 본문(400 E09)
 * 과목별: 관리자 권한 재확인 → 같은 과목 잠금 → 과목 없음(E04) → 폐강 대상 아님(E14) → 폐강 → OK
 * 처리 도중 관리자 세션이 무효가 되면 그 과목부터 남은 항목은 변경 없이 E02다.
 */
@Service
public class AdminClosureService {

    private final CourseJpaRepository courses;
    private final AccessSessionRepository sessions;
    private final CourseWriteService writer;

    public AdminClosureService(CourseJpaRepository courses, AccessSessionRepository sessions, CourseWriteService writer) {
        this.courses = courses;
        this.sessions = sessions;
        this.writer = writer;
    }

    public CourseClosureBatchResult closeCourses(CurrentUser admin, CourseClosureBatchRequest req) {
        if (admin.role() != UserRole.ADMIN) {
            throw CrsException.forbidden();
        }
        validate(req);

        List<CourseClosureItemResult> results = new ArrayList<>();
        boolean authorized = true;
        for (String code : req.courseCodes()) {
            // 과목마다 관리자 세션이 아직 유효한지 다시 확인한다(로그아웃·재사용 폐기·만료)
            authorized = authorized && sessionStillValid(admin);
            if (!authorized) {
                results.add(CourseClosureItemResult.failed(code, "E02"));
                continue;
            }
            Optional<String> courseInstId = courses.findByCourseCode(code).map(Course::getCourseInstId);
            if (courseInstId.isEmpty()) {
                results.add(CourseClosureItemResult.failed(code, "E04"));
                continue;
            }
            try {
                int closed = writer.close(courseInstId.get(), null); // 과목 1건 = 트랜잭션 1개
                results.add(CourseClosureItemResult.ok(code, closed));
            } catch (CrsException e) {
                // 잠금 아래 재검증 결과: E04(방어) 또는 E14. 이 과목만 실패하고 다음 과목을 계속 처리한다
                results.add(CourseClosureItemResult.failed(code, e.getCode()));
            }
        }
        return new CourseClosureBatchResult(results);
    }

    private boolean sessionStillValid(CurrentUser admin) {
        return admin.sessionId() != null
                && sessions.findById(admin.sessionId()).map(s -> s.isValid(Instant.now())).orElse(false);
    }

    /** 빈 배열, 중복 과목코드, 형식 오류는 400 E09(정의되지 않은 필드는 BE-01 설정이 막는다) */
    private static void validate(CourseClosureBatchRequest req) {
        List<Violation> v = new ArrayList<>();
        if (req.courseCodes() == null) {
            v.add(new Violation(Source.BODY, "/courseCodes", "필수 값입니다"));
        } else if (req.courseCodes().isEmpty()) {
            v.add(new Violation(Source.BODY, "/courseCodes", "1개 이상이어야 합니다"));
        } else {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < req.courseCodes().size(); i++) {
                String code = req.courseCodes().get(i);
                String pointer = "/courseCodes/" + i;
                if (code == null || !CourseInput.COURSE_CODE.matcher(code).matches()) {
                    v.add(new Violation(Source.BODY, pointer, "영문자·숫자 1~10자여야 합니다"));
                } else if (!seen.add(code)) {
                    v.add(new Violation(Source.BODY, pointer, "중복된 과목코드입니다"));
                }
            }
        }
        if (!v.isEmpty()) {
            throw CrsException.invalid(v);
        }
    }
}
