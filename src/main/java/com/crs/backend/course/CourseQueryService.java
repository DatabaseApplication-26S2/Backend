package com.crs.backend.course;

import com.crs.backend.auth.CurrentUser;
import com.crs.backend.common.ApiProblem.Source;
import com.crs.backend.common.CrsException;
import com.crs.backend.domain.enums.UserRole;
import com.crs.backend.queue.QueueNotAllowedException;
import com.crs.backend.queue.QueueService;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 과목 조회 업무(ENR-01-01 과목 검색).
 * 판정 순서(openapi.yaml): Access(401, 필터) → 학생 입장(409 E15) → 쿼리(400 E09) → 200
 */
@Service
public class CourseQueryService {

    private static final Set<String> CATEGORIES = Set.of("ALL", "GENERAL", "MAJOR");

    private final CourseQueryRepository repository;
    private final QueueService queueService;

    public CourseQueryService(CourseQueryRepository repository, QueueService queueService) {
        this.repository = repository;
        this.queueService = queueService;
    }

    /**
     * 합계와 목록이 같은 시점이어야 하므로 REPEATABLE READ 읽기 전용 트랜잭션에서 두 질의를 실행한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CoursePage search(CurrentUser user, String categoryParam, String q, String pageParam) {
        // 1. 학생은 수강신청 화면에 입장했어야 한다(교수·관리자는 필요 없음)
        String studentId = null;
        if (user.role() == UserRole.STUDENT) {
            requireAdmitted(user);
            studentId = user.userId();
        }

        // 2. 쿼리 검증
        int page = parsePage(pageParam);
        String category = parseCategory(categoryParam);

        // 3. 조회
        long total = repository.count(category, q);
        List<CourseView> items = repository.search(category, q, page, studentId);
        return CoursePage.of(items, page, total);
    }

    /**
     * CMG-01-01 담당 과목 조회.
     * 판정 순서: Access(401, 필터) → PROFESSOR(403 E02 FORBIDDEN) → 200
     */
    @Transactional(readOnly = true)
    public CourseList listForProfessor(CurrentUser user) {
        if (user.role() != UserRole.PROFESSOR) {
            throw CrsException.forbidden();
        }
        return new CourseList(repository.findByProfessor(user.userId()));
    }

    /**
     * ENR-02-03 내 신청 내역 조회.
     * 판정 순서: Access(401) → STUDENT(403) → 학생 입장(409 E15) → 200
     * (보안 필터와 ActiveUserInterceptor도 같은 검사를 하지만, 서비스에서도 한 번 더 확인한다)
     */
    @Transactional(readOnly = true)
    public MyEnrollmentList listMyEnrollments(CurrentUser user) {
        if (user.role() != UserRole.STUDENT) {
            throw CrsException.forbidden();
        }
        requireAdmitted(user);
        return new MyEnrollmentList(repository.findEnrollments(user.userId()));
    }

    /**
     * ENR-01-02 과목 상세·여석 조회. 폐강 과목도 200이다.
     * 판정 순서: Access(401, 필터) → 학생 입장(409 E15) → 과목코드 형식(400 E09) → 과목 없음(404 E04) → 200
     */
    @Transactional(readOnly = true)
    public CourseView getCourse(CurrentUser user, String courseCode) {
        String studentId = null;
        if (user.role() == UserRole.STUDENT) {
            requireAdmitted(user);
            studentId = user.userId();
        }
        CourseInput.requirePathCourseCode(courseCode);
        return repository.findByCode(courseCode, studentId)
                .orElseThrow(() -> CrsException.of(HttpStatus.NOT_FOUND, "E04", "과목을 찾을 수 없습니다"));
    }

    void requireAdmitted(CurrentUser user) {
        if (!queueService.isActive(user.userId())) {
            throw new QueueNotAllowedException(user.userId()); // → 409 E15
        }
    }

    /** page: 1 이상의 정수, 생략하면 1 */
    static int parsePage(String value) {
        if (value == null) return 1;
        try {
            int page = Integer.parseInt(value);
            if (page >= 1) return page;
        } catch (NumberFormatException ignored) {
            // 아래에서 E09
        }
        throw CrsException.invalid(Source.QUERY, "page", "1 이상의 정수여야 합니다");
    }

    /** category: ALL | GENERAL | MAJOR, 생략하거나 ALL이면 필터 없음(null) */
    static String parseCategory(String value) {
        if (value == null || value.equals("ALL")) return null;
        if (!CATEGORIES.contains(value)) {
            throw CrsException.invalid(Source.QUERY, "category", "ALL, GENERAL, MAJOR 중 하나여야 합니다");
        }
        return value;
    }
}
