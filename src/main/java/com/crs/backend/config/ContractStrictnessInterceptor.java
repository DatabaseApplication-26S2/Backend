package com.crs.backend.config;

import com.crs.backend.auth.AuthService;
import com.crs.backend.auth.CurrentUser;
import com.crs.backend.common.ApiProblem.Source;
import com.crs.backend.common.ApiProblem.Violation;
import com.crs.backend.common.CrsException;
import com.crs.backend.domain.enums.UserRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * BE-15 계약 엄격성(openapi.yaml 3절 공통 규약).
 * 정의되지 않은 쿼리 파라미터, 같은 쿼리 파라미터의 반복, 본문을 받지 않는 요청의 본문은 400 E09다.
 * 서버가 대신 무시하지 않는다. (정의되지 않은 본문 필드는 BE-01의 Jackson 설정이 막는다)
 *
 * 공통 판정 순서(openapi.yaml 7절)는 인증(401) → 역할(403) → 학생 입장(409 E15) → 입력(400 E09)이다.
 * 인증과 일부 역할 검사는 보안 필터가, /me/enrollments의 입장 검사는 ActiveUserInterceptor가 먼저 한다.
 * 서비스에서 하는 역할·입장 검사(교수 API, 과목 조회)는 이 검사보다 앞서야 하므로, 그 요청은 여기서 넘겨 준다.
 */
@Component
public class ContractStrictnessInterceptor implements HandlerInterceptor {

    /** 연산 1개의 규칙: 허용 쿼리 이름, 본문을 받는지 */
    private record Rule(String method, Pattern path, Set<String> query, boolean body) {
    }

    private static final String CODE = "[^/]+";
    private static final List<Rule> RULES = List.of(
            rule("POST",   "/auth/login", Set.of(), true),
            rule("POST",   "/auth/login-tickets/" + CODE + "/tokens", Set.of(), false),
            rule("POST",   "/auth/refresh", Set.of(), false),
            rule("POST",   "/auth/logout", Set.of(), false),
            rule("POST",   "/admission", Set.of(), false),
            rule("GET",    "/tickets/" + CODE, Set.of(), false),
            rule("GET",    "/courses", Set.of("category", "q", "page"), false),
            rule("GET",    "/courses/" + CODE, Set.of(), false),
            rule("GET",    "/me/enrollments", Set.of(), false),
            rule("POST",   "/me/enrollments", Set.of(), true),
            rule("DELETE", "/me/enrollments/" + CODE, Set.of(), false),
            rule("GET",    "/professor/courses", Set.of(), false),
            rule("POST",   "/professor/courses", Set.of(), true),
            rule("PUT",    "/professor/courses/" + CODE, Set.of(), true),
            rule("POST",   "/professor/courses/" + CODE + "/closure", Set.of(), false),
            rule("POST",   "/admin/enrollments", Set.of(), true),
            rule("GET",    "/admin/settings", Set.of(), false),
            rule("PUT",    "/admin/settings", Set.of(), true),
            rule("GET",    "/admin/closure-candidates", Set.of("page"), false),
            rule("POST",   "/admin/course-closures", Set.of(), true));

    private static Rule rule(String method, String path, Set<String> query, boolean body) {
        return new Rule(method, Pattern.compile("^/api/v1" + path + "$"), query, body);
    }

    private final AuthService authService;

    public ContractStrictnessInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Rule rule = find(request);
        if (rule == null || rejectedLaterByRoleOrAdmission(request)) {
            return true; // 계약에 없는 경로·메서드는 Spring이 404·405로 처리한다
        }
        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<String, Integer> e : queryNames(request.getQueryString()).entrySet()) {
            if (!rule.query().contains(e.getKey())) {
                violations.add(new Violation(Source.QUERY, e.getKey(), "정의되지 않은 쿼리 파라미터입니다"));
            } else if (e.getValue() > 1) {
                violations.add(new Violation(Source.QUERY, e.getKey(), "같은 쿼리 파라미터를 반복할 수 없습니다"));
            }
        }
        if (!rule.body() && hasBody(request)) {
            violations.add(new Violation(Source.BODY, "", "본문을 보내지 마십시오."));
        }
        if (!violations.isEmpty()) {
            throw CrsException.invalid(violations);
        }
        return true;
    }

    private static Rule find(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        for (Rule r : RULES) {
            if (r.method().equals(request.getMethod()) && r.path().matcher(path).matches()) {
                return r;
            }
        }
        return null;
    }

    /**
     * 서비스가 역할(403)이나 입장(E15)으로 거절할 요청인지. 그 검사가 E09보다 먼저이므로 여기서는 넘긴다.
     * - /professor/** 를 교수가 아닌 사람이 호출(서비스의 403)
     * - 과목 조회(/courses, /courses/{code})를 입장하지 않은 학생이 호출(서비스의 E15)
     */
    private boolean rejectedLaterByRoleOrAdmission(HttpServletRequest request) {
        if (!(request.getAttribute("currentUser") instanceof CurrentUser user)) {
            return false;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/professor/") && user.role() != UserRole.PROFESSOR) {
            return true;
        }
        boolean courseRead = "GET".equals(request.getMethod())
                && (path.equals("/api/v1/courses") || path.startsWith("/api/v1/courses/"));
        return courseRead && user.role() == UserRole.STUDENT && !authService.isAdmitted(user.userId(), user.sessionId());
    }

    /**
     * 원본 쿼리 문자열에서 이름별 개수를 센다.
     * request.getParameterMap()은 폼 본문 값까지 섞으므로 쓰지 않는다.
     */
    static Map<String, Integer> queryNames(String queryString) {
        Map<String, Integer> names = new LinkedHashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return names;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String raw = eq < 0 ? pair : pair.substring(0, eq);
            String name;
            try {
                name = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException malformed) {
                name = raw;
            }
            names.merge(name, 1, Integer::sum);
        }
        return names;
    }

    private static boolean hasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null;
    }
}
