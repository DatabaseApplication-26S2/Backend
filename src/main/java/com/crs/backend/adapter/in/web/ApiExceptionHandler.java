package com.crs.backend.adapter.in.web;

import com.crs.backend.domain.exception.EnrollmentException;
import com.crs.backend.domain.exception.ErrorCode;
import com.crs.backend.lock.LockAcquisitionException;
import com.crs.backend.queue.QueueNotAllowedException;
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
        return problem(code, ex.getMessage(), ex.getActiveTicketId());
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
        return problem(code, detail, null);
    }

    private ResponseEntity<Map<String, Object>> problem(ErrorCode code, String detail, Long activeTicketId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:crs:problem:" + code.name());
        body.put("title", code.getMessage());
        body.put("status", code.getHttpStatus().value());
        body.put("detail", detail);
        body.put("code", code.name());
        if (activeTicketId != null) {
            body.put("activeTicketId", String.valueOf(activeTicketId));
        }
        return ResponseEntity.status(code.getHttpStatus())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_TYPE, "application/problem+json")
                .body(body);
    }
}
