package com.crs.backend.auth;

import com.crs.backend.domain.entity.AccessSession;
import com.crs.backend.domain.enums.UserRole;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthFeedbackTest {
    @Test void cookieEndpointsIgnoreExpiredAccessAndRequireCsrf() throws Exception {
        AuthService auth = mock(AuthService.class);
        var filter = new AuthSecurity.AuthFilter(auth, "http://localhost:5173");
        for (String path : new String[]{"/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout",
                "/api/v1/auth/login-tickets/1/tokens"}) {
            var request = new MockHttpServletRequest("POST", path);
            request.addHeader("Origin", "http://localhost:5173");
            request.addHeader("X-CSRF-Protection", "1");
            request.addHeader("Authorization", "Bearer expired");
            AtomicBoolean called = new AtomicBoolean();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> called.set(true));
            assertTrue(called.get());
            var rejected = new MockHttpServletRequest("POST", path);
            rejected.addHeader("Origin", "http://localhost:5173");
            var response = new MockHttpServletResponse();
            filter.doFilter(rejected, response, (req, res) -> fail("Missing CSRF header"));
            assertEquals(403, response.getStatus());
        }
        verifyNoInteractions(auth);
    }
    @Test void professorEndpointRejectsStudent() throws Exception {
        AuthService auth = mock(AuthService.class);
        when(auth.authenticate("token")).thenReturn(new CurrentUser("student", "session", UserRole.STUDENT));
        var request = new MockHttpServletRequest("GET", "/api/v1/professor/courses");
        request.addHeader("Authorization", "Bearer token");
        var response = new MockHttpServletResponse();
        new AuthSecurity.AuthFilter(auth, "http://localhost:5173")
            .doFilter(request, response, (req, res) -> fail("Student must be rejected"));
        assertEquals(403, response.getStatus());
        assertEquals("UTF-8", response.getCharacterEncoding());
        var decoded = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(response.getContentAsByteArray())).toString();
        assertTrue(decoded.contains(com.crs.backend.common.ApiProblem.title("E02")),
            "The Korean ProblemDetails title must survive HTTP UTF-8 encoding");
    }
    @Test void jwtContainsRoleAndCannotOutliveSession() throws Exception {
        var codec = new TokenCodec(AuthService.randomToken());
        Instant expiry = Instant.now().plusSeconds(20);
        var session = AccessSession.builder().sessionId("test").userId("student")
            .role(UserRole.STUDENT).expiresAt(expiry).build();
        var jwt = SignedJWT.parse(codec.encode(session)).getJWTClaimsSet();
        assertEquals("STUDENT", jwt.getStringClaim("role"));
        assertTrue(jwt.getExpirationTime().toInstant().compareTo(expiry) <= 0);
    }
    @Test void sensitiveRecordsDoNotExposeSecrets() {
        assertFalse(new AuthService.LoginReceipt("1", null, null, null, null, "secret-proof")
            .toString().contains("secret-proof"));
        assertFalse(new AuthService.IssuedTokens("secret-access", "secret-refresh", null, null, null, 900)
            .toString().contains("secret-"));
    }
    @Test void expiredAccessIsRejectedEvenWithinDefaultClockSkew() throws Exception {
        String secret = AuthService.randomToken();
        var codec = new TokenCodec(secret);
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("enrollment")
            .audience("enrollment-api").subject("student").claim("sid", "test")
            .issueTime(java.util.Date.from(Instant.now().minusSeconds(60)))
            .expirationTime(java.util.Date.from(Instant.now().minusSeconds(1))).build();
        var jwt = new SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256), claims);
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(java.util.Base64.getUrlDecoder().decode(secret)));
        AuthFailure failure = assertThrows(AuthFailure.class, () -> codec.decode(jwt.serialize()));
        assertEquals("ACCESS_TOKEN_EXPIRED", failure.body().get("reason"));
    }
}
