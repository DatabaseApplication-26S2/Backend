package com.project1.enrollment.auth;

import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    public static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(c -> c.getName().equals(name)).map(Cookie::getValue).findFirst().orElse(null);
    }
    private String cookieHeader(String name, String value, long seconds) {
        return ResponseCookie.from(name, value).httpOnly(true).secure(true).sameSite("Lax").path("/")
            .maxAge(Math.max(0, seconds)).build().toString();
    }
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            var receipt = auth.login(request.userId(), request.password());
            return ResponseEntity.accepted().header(HttpHeaders.SET_COOKIE, cookieHeader("__Host-crs_login", receipt.proof(), 600))
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.LOCATION, receipt.statusUrl())
                .body(Map.of("ticketId", receipt.ticketId(), "requestType", receipt.requestType(),
                    "status", receipt.status(), "submittedAt", receipt.issuedAt(), "statusUrl", receipt.statusUrl(), "credentialExpiresAt", receipt.issuedAt().plusSeconds(300), "proofExpiresAt", receipt.issuedAt().plusSeconds(600)));
        } finally { if (request.password() != null) Arrays.fill(request.password(), '\0'); }
    }
    @PostMapping("/login-tickets/{id}/tokens")
    public ResponseEntity<?> exchange(@PathVariable long id, HttpServletRequest request) {
        return response(auth.exchange(id, cookie(request, "__Host-crs_login")));
    }
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request) {
        return response(auth.refresh(cookie(request, "__Host-crs_refresh")));
    }
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        auth.logout(cookie(request, "__Host-crs_refresh"));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookieHeader("__Host-crs_refresh", "", 0))
            .header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }
    private ResponseEntity<?> response(AuthService.IssuedTokens tokens) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.SET_COOKIE, cookieHeader("__Host-crs_refresh", tokens.refreshToken(),
                Duration.between(Instant.now(), tokens.sessionExpiresAt()).getSeconds()))
            .body(Map.of("accessToken", tokens.accessToken(), "tokenType", "Bearer", "expiresIn", 900));
    }
    public record LoginRequest(String userId, char[] password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
}
