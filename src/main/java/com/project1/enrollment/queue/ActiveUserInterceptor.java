package com.project1.enrollment.queue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 수강신청 요청 시 active_users(화면 입장) 포함 여부를 검증한다.
 * userId는 {@code X-User-Id} 헤더로 전달한다 (명세 Access 주체 대용).
 */
@Component
public class ActiveUserInterceptor implements HandlerInterceptor {

    private final QueueService queueService;

    public ActiveUserInterceptor(QueueService queueService) {
        this.queueService = queueService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String userId = request.getHeader(QueueController.USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            throw new QueueNotAllowedException("missing");
        }
        if (!queueService.isActive(userId.trim())) {
            throw new QueueNotAllowedException(userId.trim());
        }
        return true;
    }
}
