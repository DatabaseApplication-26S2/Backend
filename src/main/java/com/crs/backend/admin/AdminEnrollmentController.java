package com.crs.backend.admin;

import com.crs.backend.auth.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 API(ADM-01). /api/v1/admin/** 는 보안 필터가 ADMIN만 통과시킨다 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEnrollmentController {

    private final AdminEnrollmentService service;

    public AdminEnrollmentController(AdminEnrollmentService service) {
        this.service = service;
    }

    /** POST /admin/enrollments : 관리자 수강 배정 → 201 */
    @PostMapping("/enrollments")
    public ResponseEntity<AdminEnrollmentResult> assign(@RequestAttribute("currentUser") CurrentUser admin,
                                                        @RequestBody AdminEnrollmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.assign(admin, request));
    }
}
