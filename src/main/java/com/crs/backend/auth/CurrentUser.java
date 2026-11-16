package com.crs.backend.auth;
import com.crs.backend.domain.enums.UserRole;
public record CurrentUser(String userId, String sessionId, UserRole role) {}
