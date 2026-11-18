package com.crs.backend.auth;

import com.crs.backend.domain.entity.AccessSession;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.util.Base64;

@Component
public class TokenCodec {
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    public TokenCodec(@Value("${auth.jwt-secret:}") String secret) {
        if (secret.isBlank()) secret = AuthService.randomToken();
        byte[] bytes = Base64.getUrlDecoder().decode(secret);
        if (bytes.length < 32) throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
        encoder = new NimbusJwtEncoder(new ImmutableSecret<>(bytes));
        NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(bytes, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256).build();
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("enrollment"));
        decoder = jwtDecoder;
    }
    public String encode(AccessSession session) {
        return issue(session.getSessionId(), session.getUserId(), session.getRole(), session.getExpiresAt(), Instant.now()).value();
    }
    public Encoded issue(String id, String user, com.crs.backend.domain.enums.UserRole role, Instant sessionExpiry, Instant at) {
        Instant now = at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiry = sessionExpiry.isBefore(now.plusSeconds(900)) ? sessionExpiry.truncatedTo(java.time.temporal.ChronoUnit.SECONDS) : now.plusSeconds(900);
        if (!expiry.isAfter(now)) throw new AuthFailure(401, "SESSION_EXPIRED");
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("enrollment").subject(user)
            .audience(java.util.List.of("enrollment-api")).issuedAt(now).expiresAt(expiry)
            .claim("sid", id).claim("role", role.name()).build();
        return new Encoded(encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue(), expiry.getEpochSecond()-now.getEpochSecond());
    }
    public record Encoded(String value, long expiresIn) {
        @Override public String toString() { return "Encoded[redacted]"; }
    }
    public String decode(String token) {
        return decodeClaims(token).getClaimAsString("sid");
    }
    public Jwt decodeClaims(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            if (jwt.getExpiresAt() == null || !Instant.now().isBefore(jwt.getExpiresAt()))
                throw new AuthFailure(401, "ACCESS_TOKEN_EXPIRED");
            if (!jwt.getAudience().contains("enrollment-api")) throw new AuthFailure(401, "E02");
            if (jwt.getSubject() == null || jwt.getClaimAsString("sid") == null
                || jwt.getClaimAsString("sid").isBlank()) throw new AuthFailure(401, "E02");
            return jwt;
        } catch (JwtValidationException ex) {
            if (ex.getErrors().stream().anyMatch(error -> error.getDescription().contains("expired")))
                throw new AuthFailure(401, "ACCESS_TOKEN_EXPIRED");
            throw new AuthFailure(401, "E02");
        } catch (JwtException | IllegalArgumentException ex) { throw new AuthFailure(401, "E02"); }
    }
}
