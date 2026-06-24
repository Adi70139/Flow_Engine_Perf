package com.example.perfservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * Validates JWTs issued by the main flow-engine app.
 * Both services share the same JWT_SECRET env var — this is the industry standard
 * approach for services that trust the same token issuer without needing a separate
 * auth server. The perf service never issues tokens; it only validates them.
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${app.jwt.secret:flowengine-jwt-secret-key-change-in-production-min-32-chars}")
    private String secret;

    private SecretKey key() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT: {}", e.getMessage());
            return false;
        }
    }

    public String getEmail(String token)  { return parse(token).getSubject(); }
    public Long   getUserId(String token) { return parse(token).get("userId", Long.class); }
    public String getRole(String token)   { return parse(token).get("role", String.class); }
}