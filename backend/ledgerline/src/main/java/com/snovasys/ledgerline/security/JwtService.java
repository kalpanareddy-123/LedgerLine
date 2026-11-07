package com.snovasys.ledgerline.security;

import com.snovasys.ledgerline.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class JwtService {

    private static final java.util.Set<String> KNOWN_DEFAULTS = java.util.Set.of(
            "ledgerline_super_secure_jwt_secret_key_minimum_256_bits_length_123456",
            "test_super_secure_jwt_secret_key_minimum_256_bits_length_123456",
            "secret",
            "secretkey",
            "changeme",
            "password",
            "12345678901234567890123456789012"
    );

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${ledgerline.jwt.secret:}") String secret,
            @Value("${ledgerline.jwt.expiration-ms:1800000}") long expirationMs,
            org.springframework.core.env.Environment env) {
        
        boolean isDevOrTest = java.util.Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> p.equalsIgnoreCase("dev") || p.equalsIgnoreCase("test"));

        String effectiveSecret = (secret != null) ? secret.trim() : "";
        if (!isDevOrTest) {
            if (effectiveSecret.isEmpty() ||
                effectiveSecret.getBytes(StandardCharsets.UTF_8).length < 32 ||
                KNOWN_DEFAULTS.contains(effectiveSecret.toLowerCase())) {
                throw new IllegalStateException(
                        "FATAL SECURITY CONFIGURATION: JWT_SECRET must be supplied via environment variable, " +
                        "must be at least 32 bytes (256 bits), and cannot be a default/insecure value outside dev/test profiles."
                );
            }
        } else {
            if (effectiveSecret.isEmpty()) {
                effectiveSecret = "test_super_secure_jwt_secret_key_minimum_256_bits_length_123456";
            }
        }

        byte[] keyBytes = effectiveSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    public String generateToken(UUID userId, String username, String email, Role role) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(username)
                .claims(Map.of(
                        "userId", userId.toString(),
                        "email", email,
                        "role", role.name()
                ))
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public UUID extractUserId(String token) {
        String userIdStr = extractAllClaims(token).get("userId", String.class);
        return UUID.fromString(userIdStr);
    }

    public Role extractRole(String token) {
        String roleStr = extractAllClaims(token).get("role", String.class);
        return Role.valueOf(roleStr);
    }

    public boolean isTokenValid(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return claims.getExpiration().after(new Date());
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
