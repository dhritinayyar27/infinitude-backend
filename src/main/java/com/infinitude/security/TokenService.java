package com.infinitude.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * JWT issuance/validation (§17.2, §17.6 PROJECT_ARCHITECTURE.md). This service only deals with
 * the token itself - cookie plumbing (httpOnly/Secure/SameSite) lives in {@link AuthService}/
 * {@link AuthController} since that's an HTTP concern, not a token concern.
 */
@Service
public class TokenService {

    private static final String CLAIM_EMAIL = "email";

    private final SecretKey signingKey;
    private final long expirationSeconds;

    public TokenService(@Value("${JWT_SECRET:CHANGE_ME_INSECURE_LOCAL_DEV_ONLY_SECRET_DO_NOT_USE_IN_PRODUCTION_1234567890}") String jwtSecret,
                         @Value("${JWT_EXPIRATION_SECONDS:1800}") long expirationSeconds) {
        // HMAC-SHA key derived from the configured secret. jjwt requires >= 256 bits for HS256;
        // the inline local-dev default above is intentionally long enough, but any production
        // deployment MUST override JWT_SECRET via the environment (see application.properties
        // comment and .env.example guidance) with its own high-entropy secret, e.g.
        // `openssl rand -base64 48`. NEVER rely on this default outside a throwaway dev machine.
        this.signingKey = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.expirationSeconds = expirationSeconds;
    }

    public String generateToken(String userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim(CLAIM_EMAIL, email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expirationSeconds)))
                .signWith(signingKey)
                .compact();
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }

    /**
     * Validates the token's signature and expiry and returns its claims, or empty if the token
     * is missing/invalid/expired/tampered. Never throws outward to callers - a bad token is
     * always treated as "no valid session", never as a 500.
     */
    public Optional<Claims> parseAndValidate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public String extractUserId(Claims claims) {
        return claims.getSubject();
    }

    public String extractEmail(Claims claims) {
        return claims.get(CLAIM_EMAIL, String.class);
    }
}
