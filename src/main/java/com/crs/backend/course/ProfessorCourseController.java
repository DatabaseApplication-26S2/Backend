package com.crs.backend.course;

import com.crs.backend.auth.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 교수 과목 관리 API(CMG-02). 기준 경로 /api/v1/professor */
@RestController
@RequestMapping("/api/v1/professor")
public class ProfessorCourseController {

    private final ProfessorCourseService service;

    public ProfessorCourseController(ProfessorCourseService service) {
        this.service = service;
    }

    /** POST /professor/courses : 과목 등록 → 201, Location = 등록된 과목의 조회 경로 */
    @PostMapping("/courses")
    public ResponseEntity<CourseView> create(@RequestAttribute("currentUser") CurrentUser user,
                                             @RequestBody CourseCreateRequest request) {
        CourseView created = service.create(user, request);
        return ResponseEntity.created(URI.create("/api/v1/courses/" + created.courseCode())).body(created);
    }

    /** POST /professor/courses/{courseCode}/closure : 담당 과목 폐강(본문 없음) */
    @PostMapping("/courses/{courseCode}/closure")
    public ResponseEntity<CourseClosureResult> close(@RequestAttribute("currentUser") CurrentUser user,
                                                     @PathVariable String courseCode,
                                                     HttpServletRequest request) {
        return ResponseEntity.ok(service.close(user, courseCode, hasBody(request)));
    }

    /** 본문 유무: Content-Length > 0 이거나 chunked 전송이면 본문이 있다 */
    private static boolean hasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    }

    /** PUT /professor/courses/{courseCode} : 수정 가능한 6개 필드 전체 교체 */
    @PutMapping("/courses/{courseCode}")
    public ResponseEntity<CourseView> update(@RequestAttribute("currentUser") CurrentUser user,
                                             @PathVariable String courseCode,
                                             @RequestBody CourseUpdateRequest request) {
        return ResponseEntity.ok(service.update(user, courseCode, request));
    }
}
