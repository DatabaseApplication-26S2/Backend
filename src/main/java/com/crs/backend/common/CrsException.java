package com.crs.backend.common;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 업무 오류. GlobalExceptionHandler(BE-01)가 Problem Details로 바꾼다.
 * type = urn:crs:problem:{code}
 * 예) throw CrsException.of("E04", HttpStatus.NOT_FOUND, "과목을 찾을 수 없습니다");
 */
public class CrsException extends RuntimeException {

    /** E09 입력 위반 1건. message에 입력 값을 넣지 않는다(5절 9번). */
    public record Violation(String field, String message) {}

    private final String code;              // E01~E15, ACCESS_TOKEN_EXPIRED 등
    private final HttpStatus status;        // 400, 401, 403, 404, 409 ...
    private final String reason;            // 사람이 읽는 설명
    private final Long activeTicketId;      // E03일 때만, 알 때만 넣는다
    private final List<Violation> violations; // E09일 때만

    public CrsException(String code, HttpStatus status, String reason,
                        Long activeTicketId, List<Violation> violations) {
        super(code + ": " + reason);
        this.code = code;
        this.status = status;
        this.reason = reason;
        this.activeTicketId = activeTicketId;
        this.violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /** 일반 오류 */
    public static CrsException of(String code, HttpStatus status, String reason) {
        return new CrsException(code, status, reason, null, null);
    }

    /** 409 E03: 미완료 티켓이 있음 */
    public static CrsException activeTicket(Long activeTicketId) {
        return new CrsException("E03", HttpStatus.CONFLICT,
                "처리 중인 요청이 있습니다", activeTicketId, null);
    }

    /** 400 E09: 입력 검증 실패 */
    public static CrsException invalid(List<Violation> violations) {
        return new CrsException("E09", HttpStatus.BAD_REQUEST,
                "요청 형식이 올바르지 않습니다", null, violations);
    }

    public String getCode() { return code; }
    public HttpStatus getStatus() { return status; }
    public String getReason() { return reason; }
    public Long getActiveTicketId() { return activeTicketId; }
    public List<Violation> getViolations() { return violations; }
}