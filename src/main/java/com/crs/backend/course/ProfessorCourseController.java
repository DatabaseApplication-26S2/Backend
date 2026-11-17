package com.crs.backend.course;

import com.crs.backend.auth.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** PUT /professor/courses/{courseCode} : 수정 가능한 6개 필드 전체 교체 */
    @PutMapping("/courses/{courseCode}")
    public ResponseEntity<CourseView> update(@RequestAttribute("currentUser") CurrentUser user,
                                             @PathVariable String courseCode,
                                             @RequestBody CourseUpdateRequest request) {
        return ResponseEntity.ok(service.update(user, courseCode, request));
    }
}
