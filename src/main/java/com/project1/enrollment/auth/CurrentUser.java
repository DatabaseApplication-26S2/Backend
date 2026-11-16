package com.project1.enrollment.auth;
import com.project1.enrollment.domain.enums.UserRole;
public record CurrentUser(String userId, String sessionId, UserRole role) {}
