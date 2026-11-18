package com.crs.backend.common;

import com.crs.backend.auth.AuthFailure;
import com.crs.backend.common.ApiProblem.Source;
import com.crs.backend.common.ApiProblem.Violation;
import com.crs.backend.domain.exception.EnrollmentException;
import com.crs.backend.domain.exception.ErrorCode;
import com.crs.backend.lock.LockAcquisitionException;
import com.crs.backend.queue.QueueNotAllowedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * 모든 컨트롤러 예외를 Problem Details로 바꾸는 단일 처리기(BE-01).
 * 본문 형식은 ApiProblem 한 곳에서만 만든다.
 *
 * 우선순위를 가장 높게 두어, 기존 ApiExceptionHandler·AuthExceptionHandler가 남아 있어도 이쪽이 처리한다.
 * (두 클래스는 이 처리기로 합쳐졌으므로 삭제한다)
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------- 업무 예외 ----------

    @ExceptionHandler(CrsException.class)
    public ResponseEntity<Map<String, Object>> crs(CrsException ex) {
        return ex.toProblem().toResponse();
    }

    @ExceptionHandler(EnrollmentException.class)
    public ResponseEntity<Map<String, Object>> enrollment(EnrollmentException ex) {
        ErrorCode code = ex.getErrorCode();
        return new ApiProblem(code.getHttpStatus().value(), code.name(), ex.getMessage(), null, null, null)
                .toResponse();
    }

    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<Map<String, Object>> auth(AuthFailure ex, jakarta.servlet.http.HttpServletRequest request) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType(ApiProblem.MEDIA_TYPE));
        headers.setCacheControl("no-store");
        if (ex.status() == 401) {
            headers.set("WWW-Authenticate", request.getHeader("Authorization") == null ? "Bearer" : "Bearer error=\"invalid_token\"");
            if (request.getRequestURI().equals("/api/v1/auth/refresh"))
                headers.add("Set-Cookie", "__Host-crs_refresh=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax");
        }
        return ResponseEntity.status(ex.status()).headers(headers).body(ex.body());
    }

    @ExceptionHandler(QueueNotAllowedException.class)
    public ResponseEntity<Map<String, Object>> notAdmitted(QueueNotAllowedException ex) {
        return ApiProblem.of(HttpStatus.CONFLICT, "E15", "수강신청 화면에 먼저 입장해야 합니다").toResponse();
    }

    @ExceptionHandler(LockAcquisitionException.class)
    public ResponseEntity<Map<String, Object>> lock(LockAcquisitionException ex) {
        // 계약에 없는 코드다(팀 결정 필요). 기존 동작(429 LOCK_NOT_ACQUIRED)을 유지한다.
        return ApiProblem.of(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.LOCK_NOT_ACQUIRED.name(),
                "잠시 후 다시 시도해 주세요").toResponse();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegalArgument(IllegalArgumentException ex) {
        // 기존 동작 유지: 잘못된 인자는 E09. 내부 메시지는 응답에 넣지 않는다.
        return ApiProblem.invalid(Source.BODY, "", "요청 값이 올바르지 않습니다").toResponse();
    }

    // ---------- 입력 오류(E09) ----------

    /** 본문이 JSON이 아님, 정의되지 않은 필드, 타입 불일치, 본문 없음 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        for (Throwable t = ex.getCause(); t != null; t = t.getCause()) {
            if (t instanceof UnrecognizedPropertyException u) {
                return ApiProblem.invalid(Source.BODY, "/" + u.getPropertyName(), "정의되지 않은 필드입니다")
                        .toResponse();
            }
            if (t instanceof MismatchedInputException) {
                return ApiProblem.invalid(Source.BODY, "", "필드 값의 형식이 올바르지 않습니다").toResponse();
            }
            if (t instanceof JacksonException) {
                return ApiProblem.invalid(Source.BODY, "", "본문이 올바른 JSON이 아닙니다").toResponse();
            }
        }
        return ApiProblem.invalid(Source.BODY, "", "요청 본문이 없거나 읽을 수 없습니다").toResponse();
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> mediaType(HttpMediaTypeNotSupportedException ex) {
        return ApiProblem.invalid(Source.BODY, "", "application/json 본문만 받습니다").toResponse();
    }

    /** @Valid 검증 실패 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> notValid(MethodArgumentNotValidException ex) {
        List<Violation> violations = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            String pointer = "/" + fe.getField().replace('.', '/');
            String message = fe.getDefaultMessage() == null ? "값이 올바르지 않습니다" : fe.getDefaultMessage();
            violations.add(new Violation(Source.BODY, pointer, message));
        }
        return ApiProblem.invalid(violations).toResponse();
    }

    /** 경로·쿼리 값의 타입 불일치(예: page=abc) */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> typeMismatch(MethodArgumentTypeMismatchException ex) {
        Source source = ex.getParameter().hasParameterAnnotation(PathVariable.class) ? Source.PATH : Source.QUERY;
        return ApiProblem.invalid(source, ex.getName(), "값의 형식이 올바르지 않습니다").toResponse();
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParam(MissingServletRequestParameterException ex) {
        return ApiProblem.invalid(Source.QUERY, ex.getParameterName(), "필수 값입니다").toResponse();
    }

    // ---------- 그 밖의 모든 예외 ----------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception ex) {
        // Spring이 상태 코드를 정한 예외(없는 경로 404, 메서드 불일치 405 등)는 그 상태를 유지한다
        if (ex instanceof ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            int status = er.getStatusCode().value();
            return new ApiProblem(status, "E09", "지원하지 않는 요청입니다", null, null, null).toResponse();
        }
        log.error("처리되지 않은 예외", ex);
        return ApiProblem.internalError().toResponse(); // 내부 정보는 응답에 넣지 않는다
    }
}
