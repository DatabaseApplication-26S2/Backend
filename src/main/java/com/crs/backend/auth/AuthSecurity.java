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
    SecurityFilterChain security(HttpSecurity http, AuthService auth) throws Exception {
        return http.csrf(csrf -> csrf.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .addFilterBefore(new AuthFilter(auth), UsernamePasswordAuthenticationFilter.class).build();
    }
    static class AuthFilter extends OncePerRequestFilter {
        private final AuthService auth;
        AuthFilter(AuthService auth) { this.auth = auth; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!request.getRequestURI().startsWith("/api/v1/")) { chain.doFilter(request, response); return; }
            try {
                String origin = request.getHeader("Origin");
                String expected = request.getScheme() + "://" + request.getServerName()
                    + ((request.getServerPort() == 80 || request.getServerPort() == 443) ? "" : ":" + request.getServerPort());
                if (!request.getMethod().equals("GET") && origin != null && !origin.equals(expected))
                    throw new AuthFailure(403, "E02");
                String bearer = request.getHeader("Authorization");
                CurrentUser user = null;
                if (bearer != null) {
                    if (!bearer.startsWith("Bearer ")) throw new AuthFailure(401, "E02");
                    user = auth.authenticate(bearer.substring(7));
                }
                String path = request.getRequestURI();
                boolean publicPath = path.startsWith("/api/v1/auth/") || path.startsWith("/api/v1/tickets/");
                if (!publicPath && user == null) throw new AuthFailure(401, "E02");
                if (user != null && (path.equals("/api/v1/admission") || path.startsWith("/api/v1/me/enrollments"))
                    && user.role() != UserRole.STUDENT) throw new AuthFailure(403, "E02");
                if (user != null && path.startsWith("/api/v1/admin/") && user.role() != UserRole.ADMIN)
                    throw new AuthFailure(403, "E02");
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
                // BE-01: 오류 본문은 ApiProblem 한 곳에서 만든다
                com.crs.backend.common.ApiProblem.fromAuthFailure(ex.status(), ex.code()).writeTo(response);
            }
        }
    }
}
