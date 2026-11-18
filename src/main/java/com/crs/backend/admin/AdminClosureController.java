package com.crs.backend.admin;

import com.crs.backend.auth.CurrentUser;
import com.crs.backend.course.ClosureCandidatePage;
import com.crs.backend.course.CourseQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 폐강 관리 API(ADM-03). /api/v1/admin/** 는 보안 필터가 ADMIN만 통과시킨다 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminClosureController {

    private final CourseQueryService query;
    private final AdminClosureService closures;

    public AdminClosureController(CourseQueryService query, AdminClosureService closures) {
        this.query = query;
        this.closures = closures;
    }

    /** GET /admin/closure-candidates?page= : 폐강 대상(OPEN이고 현재 인원 < 최소 수강인원) 20건 페이지 */
    @GetMapping("/closure-candidates")
    public ResponseEntity<ClosureCandidatePage> candidates(@RequestAttribute("currentUser") CurrentUser admin,
                                                           @RequestParam(required = false) String page) {
        return ResponseEntity.ok(query.closureCandidates(admin, page));
    }

    /** POST /admin/course-closures : 일괄 폐강. 일부·전부 실패해도 200과 입력 순서의 과목별 결과 */
    @PostMapping("/course-closures")
    public ResponseEntity<CourseClosureBatchResult> close(@RequestAttribute("currentUser") CurrentUser admin,
                                                          @RequestBody CourseClosureBatchRequest request) {
        return ResponseEntity.ok(closures.closeCourses(admin, request));
    }
}
