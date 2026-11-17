package com.crs.backend.common;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * 오류 응답 본문(RFC 9457 Problem Details)을 만드는 유일한 곳이다(BE-01).
 * 컨트롤러 예외(GlobalExceptionHandler)와 필터 예외(AuthSecurity)가 모두 이 클래스를 쓴다.
 *
 * 계약: openapi.yaml 6절 오류 규약, components.schemas.ProblemDetails
 * - type = urn:crs:problem:{code}, status = HTTP 상태
 * - reason은 E02에만, activeTicketId는 E03에만, violations는 E09에 필수
 * - 모든 오류 응답에 Cache-Control: no-store, 401에는 WWW-Authenticate
 */
public record ApiProblem(int status, String code, String detail, String reason,
                         Long activeTicketId, List<Violation> violations) {

    /** E09 위반 위치 */
    public enum Source { BODY, QUERY, PATH }

    /** E09 입력 위반 1건. message에 입력 값(특히 비밀번호)을 되풀이하지 않는다. */
    public record Violation(Source source, String field, String message) {}

    public static final String MEDIA_TYPE = "application/problem+json";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Pattern RESULT_CODE = Pattern.compile("E\\d{2}");

    /** 코드별 고정 제목 */
    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("E01", "인증 실패"),
            Map.entry("E02", "인증·권한 없음"),
            Map.entry("E03", "처리 중인 요청 있음"),
            Map.entry("E04", "과목 없음"),
            Map.entry("E05", "폐강 과목"),
            Map.entry("E06", "이미 신청한 과목"),
            Map.entry("E09", "입력값 오류"),
            Map.entry("E10", "과목코드 중복"),
            Map.entry("E11", "정원이 현재 인원보다 작음"),
            Map.entry("E13", "학생 계정 아님"),
            Map.entry("E14", "폐강 대상 아님"),
            Map.entry("E15", "수강신청 화면 미입장"),
            Map.entry(INTERNAL_ERROR, "서버 내부 오류"));

    public ApiProblem {
        violations = violations == null ? List.of() : List.copyOf(violations);
        // E09는 violations가 최소 1건 필요하다
        if ("E09".equals(code) && violations.isEmpty()) {
            violations = List.of(new Violation(Source.BODY, "", "요청이 올바르지 않습니다"));
        }
        // 403 E02는 사유가 없으면 FORBIDDEN(역할·소유권 불일치)
        if ("E02".equals(code) && status == 403 && reason == null) {
            reason = "FORBIDDEN";
        }
        if (!"E02".equals(code)) reason = null;
        if (!"E03".equals(code)) activeTicketId = null;
        if (!"E09".equals(code)) violations = List.of();
        if (detail == null || detail.isBlank()) detail = title(code);
    }

    // ---------- 만들기 ----------

    public static ApiProblem of(HttpStatus status, String code, String detail) {
        return new ApiProblem(status.value(), code, detail, null, null, null);
    }

    public static ApiProblem invalid(List<Violation> violations) {
        return new ApiProblem(400, "E09", "요청 형식이 올바르지 않습니다", null, null, violations);
    }

    public static ApiProblem invalid(Source source, String field, String message) {
        return invalid(List.of(new Violation(source, field, message)));
    }

    public static ApiProblem internalError() {
        return new ApiProblem(500, INTERNAL_ERROR, "서버 내부 오류가 발생했습니다", null, null, null);
    }

    /**
     * AuthFailure(status, code)를 계약 형식으로 바꾼다.
     * code가 E01·E02 같은 결과 코드면 그대로, LOGIN_PROOF_INVALID 같은 사유면 E02 + reason으로 옮긴다.
     */
    public static ApiProblem fromAuthFailure(int status, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return new ApiProblem(status, "E02", null, null, null, null);
        }
        if (RESULT_CODE.matcher(rawCode).matches() || INTERNAL_ERROR.equals(rawCode)) {
            return new ApiProblem(status, rawCode, null, null, null, null);
        }
        return new ApiProblem(status, "E02", null, rawCode, null, null);
    }

    // ---------- 내보내기 ----------

    public static String title(String code) {
        return TITLES.getOrDefault(code, "요청을 처리할 수 없음");
    }

    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:crs:problem:" + code);
        body.put("title", title(code));
        body.put("status", status);
        body.put("detail", detail);
        body.put("code", code);
        if (reason != null) body.put("reason", reason);
        if (activeTicketId != null) body.put("activeTicketId", String.valueOf(activeTicketId)); // 티켓 ID는 10진수 문자열
        if (!violations.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Violation v : violations) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("source", v.source().name());
                item.put("field", v.field() == null ? "" : v.field());
                item.put("message", v.message());
                list.add(item);
            }
            body.put("violations", list);
        }
        return body;
    }

    /** 컨트롤러·예외 핸들러용 */
    public ResponseEntity<Map<String, Object>> toResponse() {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(MediaType.parseMediaType(MEDIA_TYPE))
                .header(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == 401) builder.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return builder.body(toBody());
    }

    /** 서블릿 필터용(컨트롤러에 닿기 전에 응답할 때) */
    public void writeTo(HttpServletResponse response) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MEDIA_TYPE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (status == 401) response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.getWriter().write(toJson(toBody()));
    }

    // 필터에서 쓸 작은 JSON 직렬화기(문자열·숫자·불리언·null·Map·List만 다룬다)
    private static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        appendJson(sb, value);
        return sb.toString();
    }

    private static void appendJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                appendString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                appendJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                appendJson(sb, list.get(i));
            }
            sb.append(']');
        } else {
            appendString(sb, String.valueOf(value));
        }
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
