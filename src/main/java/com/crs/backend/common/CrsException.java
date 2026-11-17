package com.crs.backend.common;

import com.crs.backend.common.ApiProblem.Violation;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * 업무 오류. GlobalExceptionHandler가 Problem Details(ApiProblem)로 바꾼다.
 * type = urn:crs:problem:{code}
 *
 * 예)
 *   throw CrsException.of(HttpStatus.NOT_FOUND, "E04", "과목을 찾을 수 없습니다");
 *   throw CrsException.forbidden();                       // 403 E02 FORBIDDEN
 *   throw CrsException.activeTicket(123L);                // 409 E03 + activeTicketId
 *   throw CrsException.invalid(Source.BODY, "/capacity", "1 이상이어야 합니다"); // 400 E09
 */
public class CrsException extends RuntimeException {

    private final transient ApiProblem problem;

    public CrsException(ApiProblem problem) {
        super(problem.code() + ": " + problem.detail());
        this.problem = problem;
    }

    public ApiProblem toProblem() {
        return problem;
    }

    public String getCode() {
        return problem.code();
    }

    /** 일반 오류 */
    public static CrsException of(HttpStatus status, String code, String detail) {
        return new CrsException(ApiProblem.of(status, code, detail));
    }

    /** 403 E02 FORBIDDEN: 역할·소유권 불일치(예: 남의 과목 수정) */
    public static CrsException forbidden() {
        return new CrsException(new ApiProblem(403, "E02", "권한이 없습니다", "FORBIDDEN", null, null));
    }

    /** 409 E03: 미완료 티켓이 있음. 번호를 모르면 null */
    public static CrsException activeTicket(Long activeTicketId) {
        return new CrsException(new ApiProblem(409, "E03", "처리 중인 요청이 있습니다", null, activeTicketId, null));
    }

    /** 400 E09: 입력 검증 실패 */
    public static CrsException invalid(List<Violation> violations) {
        return new CrsException(ApiProblem.invalid(violations));
    }

    public static CrsException invalid(ApiProblem.Source source, String field, String message) {
        return new CrsException(ApiProblem.invalid(source, field, message));
    }
}
