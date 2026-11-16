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
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("enrollment").subject(session.getUserId())
            .audience(java.util.List.of("enrollment-api")).issuedAt(now).expiresAt(now.plusSeconds(900))
            .claim("sid", session.getSessionId()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    public String decode(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            if (!jwt.getAudience().contains("enrollment-api")) throw new AuthFailure(401, "E02");
            return jwt.getClaimAsString("sid");
        } catch (JwtException | IllegalArgumentException ex) { throw new AuthFailure(401, "E02"); }
    }
}
