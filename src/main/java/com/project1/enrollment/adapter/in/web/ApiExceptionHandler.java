package com.project1.enrollment.adapter.in.web;

import com.project1.enrollment.domain.exception.EnrollmentException;
import com.project1.enrollment.domain.exception.ErrorCode;
import com.project1.enrollment.lock.LockAcquisitionException;
import com.project1.enrollment.queue.QueueNotAllowedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EnrollmentException.class)
    public ResponseEntity<Map<String, Object>> handleEnrollment(EnrollmentException ex) {
        ErrorCode code = ex.getErrorCode();
        return problem(code, ex.getMessage());
    }

    @ExceptionHandler(QueueNotAllowedException.class)
    public ResponseEntity<Map<String, Object>> handleQueueNotAllowed(QueueNotAllowedException ex) {
        return problem(ErrorCode.E15, ex.getMessage());
    }

    @ExceptionHandler(LockAcquisitionException.class)
    public ResponseEntity<Map<String, Object>> handleLock(LockAcquisitionException ex) {
        return problem(ErrorCode.LOCK_NOT_ACQUIRED, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return problem(ErrorCode.E09, ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> problem(ErrorCode code, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:crs:problem:" + code.name());
        body.put("title", code.getMessage());
        body.put("status", code.getHttpStatus().value());
        body.put("detail", detail);
        body.put("code", code.name());
        return ResponseEntity.status(code.getHttpStatus())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_TYPE, "application/problem+json")
                .body(body);
    }
}
