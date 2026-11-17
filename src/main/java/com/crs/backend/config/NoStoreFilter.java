package com.crs.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 응답(오류 포함)에 Cache-Control: no-store를 붙인다(BE-01, openapi.yaml 3절 공통 규약).
 * 보안 필터보다 먼저 실행되도록 우선순위를 가장 높게 둔다.
 * Spring Security의 기본 Cache-Control 헤더는 이미 값이 있으면 덮어쓰지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NoStoreFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        chain.doFilter(request, response);
    }
}
