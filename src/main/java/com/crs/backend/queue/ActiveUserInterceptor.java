package com.crs.backend.queue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 보안 필터가 검증한 사용자/세션의 DB 입장 상태를 확인한다.
 */
@Component
public class ActiveUserInterceptor implements HandlerInterceptor {

    private final com.crs.backend.auth.AuthService auth;

    public ActiveUserInterceptor(com.crs.backend.auth.AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader(QueueController.USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            throw new QueueNotAllowedException("missing");
        }
        if (!auth.isAdmitted(userId, request.getHeader(QueueController.SESSION_ID_HEADER))) {
            throw new QueueNotAllowedException(userId.trim());
        }
        return true;
    }
}
