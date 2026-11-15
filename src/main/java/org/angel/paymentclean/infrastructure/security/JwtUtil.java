package org.angel.paymentclean.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.angel.paymentclean.domain.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    private static final String DEFAULT_SECRET = "mi_clave_secreta_super_segura_de_al_menos_32_caracteres_123456";
    private static final long DEFAULT_EXPIRATION_MS = 1000L * 60 * 60 * 10; // 10 horas

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil() {
        this(DEFAULT_SECRET, DEFAULT_EXPIRATION_MS);
    }

    @Autowired
    public JwtUtil(
            @Value("${jwt.secret:" + DEFAULT_SECRET + "}") String secret,
            @Value("${jwt.expiration-ms:" + DEFAULT_EXPIRATION_MS + "}") long expirationMs
    ) {
        String effectiveSecret = (secret != null && !secret.isBlank()) ? secret : DEFAULT_SECRET;
        this.key = Keys.hmacShaKeyFor(effectiveSecret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs > 0 ? expirationMs : DEFAULT_EXPIRATION_MS;
    }

    public String generateToken(String username) {
        return generateToken(username, Role.CLIENT);
    }

    public String generateToken(String username, Role role) {
        String roleName = (role != null) ? role.name() : Role.CLIENT.name();
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("role", roleName)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMs))
                .signWith(key)
                .compact();
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public String extractRole(String token) {
        String role = extractAllClaims(token).get("role", String.class);
        return (role != null && !role.isBlank()) ? role : Role.CLIENT.name();
    }

    public Instant extractExpiration(String token) {
        Date expiration = extractAllClaims(token).getExpiration();
        return expiration != null ? expiration.toInstant() : null;
    }

    /**
     * Valida firma y vigencia del JWT; lanza {@link io.jsonwebtoken.ExpiredJwtException}
     * si caducó o {@link JwtException} si es inválido o fue manipulado.
     */
    public Claims parseValidClaims(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("JWT token is blank");
        }
        return extractAllClaims(token.trim());
    }

    public boolean isTokenValid(String token) {
        try {
            parseValidClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return false;
        }
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
