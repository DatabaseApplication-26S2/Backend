package com.crs.backend.adapter.in.web;

import com.crs.backend.adapter.in.web.dto.EnrollmentRequest;
import com.crs.backend.adapter.in.web.dto.TicketReceipt;
import com.crs.backend.adapter.out.persistence.jpa.CourseJpaRepository;
import com.crs.backend.domain.entity.Course;
import com.crs.backend.domain.exception.EnrollmentException;
import com.crs.backend.domain.exception.ErrorCode;
import com.crs.backend.domain.service.EnrollmentService;
import com.crs.backend.queue.QueueController;
import com.crs.backend.queue.QueueService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수강신청 API — ENR-02-01, ENR-02-02
 * 기준 경로: /api/v1/me/enrollments
 */
@RestController
@RequestMapping("/api/v1/me/enrollments")
public class EnrollmentController {

    private final EnrollmentService enrollmentService;
    private final CourseJpaRepository courseRepository;
    private final QueueService queueService;

    public EnrollmentController(
            EnrollmentService enrollmentService,
            CourseJpaRepository courseRepository,
            QueueService queueService
    ) {
        this.enrollmentService = enrollmentService;
        this.courseRepository = courseRepository;
        this.queueService = queueService;
    }

    @PostMapping
    public ResponseEntity<TicketReceipt> requestEnrollment(
            @RequestHeader(QueueController.USER_ID_HEADER) String userId,
            @RequestHeader(value = QueueController.SESSION_ID_HEADER, required = false) String sessionId,
            @RequestBody EnrollmentRequest request
    ) {
        if (request == null || request.courseCode() == null || request.courseCode().isBlank()) {
            throw new EnrollmentException(ErrorCode.E09, "courseCode must not be blank");
        }

        Course course = courseRepository.findByCourseCode(request.courseCode())
                .orElseThrow(() -> new EnrollmentException(ErrorCode.E04));

        TicketReceipt receipt = queueService.createEnrollmentTicket(
                userId,
                sessionId,
                course.getCourseInstId(),
                course.getCourseCode()
        );

        try {
            enrollmentService.enrollStudent(userId, course.getCourseInstId(), Long.valueOf(receipt.ticketId()));
        } finally {
            queueService.removeFromWaitingQueue(receipt.ticketId());
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, receipt.statusUrl())
                .header(HttpHeaders.RETRY_AFTER, "2")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(receipt);
    }

    @DeleteMapping("/{courseCode}")
    public ResponseEntity<TicketReceipt> requestCancellation(
            @RequestHeader(QueueController.USER_ID_HEADER) String userId,
            @RequestHeader(value = QueueController.SESSION_ID_HEADER, required = false) String sessionId,
            @PathVariable String courseCode
    ) {
        Course course = courseRepository.findByCourseCode(courseCode)
                .orElseThrow(() -> new EnrollmentException(ErrorCode.E04));

        TicketReceipt receipt = queueService.createCancellationTicket(
                userId, sessionId, course.getCourseInstId(), course.getCourseCode()
        );
        try {
            enrollmentService.cancelStudent(userId, course.getCourseInstId(), Long.valueOf(receipt.ticketId()));
        } finally {
            queueService.removeFromWaitingQueue(receipt.ticketId());
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, receipt.statusUrl())
                .header(HttpHeaders.RETRY_AFTER, "2")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(receipt);
    }
}
