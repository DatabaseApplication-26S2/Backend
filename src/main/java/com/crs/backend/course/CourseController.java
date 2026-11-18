package com.crs.backend.course;

import com.crs.backend.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 과목 조회 API(ENR-01). 기준 경로 /api/v1
 * 로그인 확인은 AuthSecurity 필터가 하고, 확인된 사용자를 request 속성 "currentUser"에 넣어 준다.
 * Cache-Control: no-store는 NoStoreFilter가 붙인다.
 */
@RestController
@RequestMapping("/api/v1")
public class CourseController {

    private final CourseQueryService service;

    public CourseController(CourseQueryService service) {
        this.service = service;
    }

    /** GET /courses?category=&q=&page= : 과목 검색(폐강 포함, 20건 페이지) */
    @GetMapping("/courses")
    public ResponseEntity<CoursePage> search(
            @RequestAttribute("currentUser") CurrentUser user,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String page) {
        return ResponseEntity.ok(service.search(user, category, q, page));
    }

    /** GET /courses/{courseCode} : 과목 상세·여석(폐강 과목도 200) */
    @GetMapping("/courses/{courseCode}")
    public ResponseEntity<CourseView> getCourse(@RequestAttribute("currentUser") CurrentUser user,
                                                @PathVariable String courseCode) {
        return ResponseEntity.ok(service.getCourse(user, courseCode));
    }

    /** GET /professor/courses : 로그인한 교수의 담당 과목 전체(폐강 포함, 페이지 없음) */
    @GetMapping("/professor/courses")
    public ResponseEntity<CourseList> professorCourses(@RequestAttribute("currentUser") CurrentUser user) {
        return ResponseEntity.ok(service.listForProfessor(user));
    }

    /** GET /me/enrollments : 학생 본인의 현재 신청(ENROLLED) 과목 전체 */
    @GetMapping("/me/enrollments")
    public ResponseEntity<MyEnrollmentList> myEnrollments(@RequestAttribute("currentUser") CurrentUser user) {
        return ResponseEntity.ok(service.listMyEnrollments(user));
    }
}
