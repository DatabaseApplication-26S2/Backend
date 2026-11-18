package com.crs.backend.auth;

import com.crs.backend.domain.enums.UserRole;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Configuration
public class AuthSecurity {
    @Bean
    SecurityFilterChain security(HttpSecurity http, AuthService auth, @org.springframework.beans.factory.annotation.Value("${app.frontend-origin}") String frontendOrigin) throws Exception {
        return http.cors(cors -> cors.configurationSource(request -> {
            var config = new org.springframework.web.cors.CorsConfiguration();
            config.setAllowedOrigins(java.util.List.of(frontendOrigin));
            config.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
            config.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type", "X-CSRF-Protection"));
            config.setExposedHeaders(java.util.List.of("Location", "Retry-After"));
            config.setAllowCredentials(true);
            return config;
        })).csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .addFilterBefore(new AuthFilter(auth, frontendOrigin), org.springframework.web.filter.CorsFilter.class).build();
    }
    static class AuthFilter extends OncePerRequestFilter {
        private final AuthService auth;
        private final String frontendOrigin;
        AuthFilter(AuthService auth, String frontendOrigin) { this.auth = auth; this.frontendOrigin = frontendOrigin; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!request.getRequestURI().startsWith("/api/v1/") || org.springframework.web.cors.CorsUtils.isPreFlightRequest(request)) { chain.doFilter(request, response); return; }
            try {
                String path = request.getRequestURI();
                if (path.startsWith("/api/v1/tickets/")) AuthController.parseTicketId(path.substring("/api/v1/tickets/".length()));
                boolean cookieOperation = path.equals("/api/v1/auth/login")
                    || path.equals("/api/v1/auth/refresh") || path.equals("/api/v1/auth/logout")
                    || path.matches("/api/v1/auth/login-tickets/[^/]+/tokens");
                if (cookieOperation && (!frontendOrigin.equals(request.getHeader("Origin"))
                    || !"1".equals(request.getHeader("X-CSRF-Protection"))))
                    throw new AuthFailure(403, "CSRF_CHECK_FAILED");
                String bearer = request.getHeader("Authorization");
                CurrentUser user = null;
                if (bearer != null && !cookieOperation) {
                    if (!bearer.startsWith("Bearer ")) throw new AuthFailure(401, "E02");
                    user = auth.authenticate(bearer.substring(7));
                }
                boolean publicPath = path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/tickets/");
                if (!publicPath && user == null) throw new AuthFailure(401, "E02");
                if (user != null && (path.equals("/api/v1/admission") || path.startsWith("/api/v1/me/enrollments"))
                    && user.role() != UserRole.STUDENT) throw new AuthFailure(403, "FORBIDDEN");
                if (user != null && path.startsWith("/api/v1/admin/") && user.role() != UserRole.ADMIN)
                    throw new AuthFailure(403, "FORBIDDEN");
                if (user != null && path.startsWith("/api/v1/professor/") && user.role() != UserRole.PROFESSOR)
                    throw new AuthFailure(403, "FORBIDDEN");
                boolean authInput = cookieOperation || path.equals("/api/v1/admission") || path.startsWith("/api/v1/tickets/");
                if (authInput && request.getQueryString() != null && !request.getQueryString().isEmpty())
                    throw AuthFailure.invalid(com.crs.backend.common.ApiProblem.Source.QUERY, "");
                if (authInput && !path.equals("/api/v1/auth/login") && request.getInputStream().read() != -1)
                    throw AuthFailure.invalid(com.crs.backend.common.ApiProblem.Source.BODY, "");
                request.setAttribute("currentUser", user);
                CurrentUser principal = user;
                chain.doFilter(new HttpServletRequestWrapper(request) {
                    @Override public java.util.Enumeration<String> getHeaders(String name) {
                        if (name.equalsIgnoreCase("X-User-Id") || name.equalsIgnoreCase("X-Session-Id")) {
                            String value = getHeader(name);
                            return java.util.Collections.enumeration(value == null ? java.util.List.of() : java.util.List.of(value));
                        }
                        return super.getHeaders(name);
                    }
                    @Override public String getHeader(String name) {
                        if (name.equalsIgnoreCase("X-User-Id")) return principal == null ? null : principal.userId();
                        if (name.equalsIgnoreCase("X-Session-Id")) return principal == null ? null : principal.sessionId();
                        return super.getHeader(name);
                    }
                }, response);
            } catch (AuthFailure ex) {
                if (frontendOrigin.equals(request.getHeader("Origin"))) {
                    response.setHeader("Access-Control-Allow-Origin", frontendOrigin);
                    response.setHeader("Access-Control-Allow-Credentials", "true");
                    response.addHeader("Vary", "Origin");
                }
                response.setStatus(ex.status());
                response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
                response.setContentType("application/problem+json");
                response.setHeader("Cache-Control", "no-store");
                if (ex.status() == 401) response.setHeader("WWW-Authenticate", request.getHeader("Authorization") == null ? "Bearer" : "Bearer error=\"invalid_token\"");
                response.getWriter().write(new tools.jackson.databind.ObjectMapper().writeValueAsString(ex.body()));
            }
        }
    }
}
