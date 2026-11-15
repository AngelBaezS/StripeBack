package org.angel.paymentclean.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.angel.paymentclean.domain.enums.Role;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private static final String SECRET = "mi_clave_secreta_super_segura_de_al_menos_32_caracteres_123456";

    @Test
    void generateAndValidateToken_shouldSucceedAndProduceDistinctTokens() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000L);

        String token1 = jwtUtil.generateToken("angel", Role.ADMIN);
        String token2 = jwtUtil.generateToken("angel", Role.ADMIN);

        assertNotNull(token1);
        assertNotEquals(token1, token2);
        assertTrue(jwtUtil.isTokenValid(token1));

        Claims claims = jwtUtil.parseValidClaims(token1);
        assertEquals("angel", claims.getSubject());
        assertEquals("ADMIN", claims.get("role", String.class));
        assertNotNull(jwtUtil.extractExpiration(token1));
    }

    @Test
    void parseValidClaims_whenExpired_shouldThrowExpiredJwtException() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000L);
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        long past = System.currentTimeMillis() - 10_000L;

        String expiredToken = Jwts.builder()
                .subject("angel")
                .claim("role", "CLIENT")
                .issuedAt(new Date(past - 60_000L))
                .expiration(new Date(past))
                .signWith(key)
                .compact();

        assertFalse(jwtUtil.isTokenValid(expiredToken));
        assertThrows(ExpiredJwtException.class, () -> jwtUtil.parseValidClaims(expiredToken));
    }

    @Test
    void parseValidClaims_whenTampered_shouldReturnFalseAndThrow() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 60_000L);
        String token = jwtUtil.generateToken("angel", Role.CLIENT);
        String tampered = token.substring(0, token.length() - 4) + "abcd";

        assertFalse(jwtUtil.isTokenValid(tampered));
        assertThrows(Exception.class, () -> jwtUtil.parseValidClaims(tampered));
    }
}

