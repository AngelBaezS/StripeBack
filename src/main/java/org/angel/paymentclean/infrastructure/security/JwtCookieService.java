package org.angel.paymentclean.infrastructure.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class JwtCookieService {

    public static final String DEFAULT_COOKIE_NAME = "auth_token";
    private static final long DEFAULT_EXPIRATION_MS = 1000L * 60 * 60 * 10;

    private final String cookieName;
    private final boolean secure;
    private final String sameSite;
    private final long expirationMs;

    public JwtCookieService() {
        this(DEFAULT_COOKIE_NAME, true, "Strict", DEFAULT_EXPIRATION_MS);
    }

    @Autowired
    public JwtCookieService(
            @Value("${jwt.cookie.name:" + DEFAULT_COOKIE_NAME + "}") String cookieName,
            @Value("${jwt.cookie.secure:true}") boolean secure,
            @Value("${jwt.cookie.same-site:Strict}") String sameSite,
            @Value("${jwt.expiration-ms:" + DEFAULT_EXPIRATION_MS + "}") long expirationMs
    ) {
        this.cookieName = (cookieName != null && !cookieName.isBlank()) ? cookieName.trim() : DEFAULT_COOKIE_NAME;
        this.secure = secure;
        this.sameSite = (sameSite != null && !sameSite.isBlank()) ? sameSite.trim() : "Strict";
        this.expirationMs = expirationMs > 0 ? expirationMs : DEFAULT_EXPIRATION_MS;
    }

    public String getCookieName() {
        return cookieName;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public ResponseCookie createAuthCookie(String token) {
        return ResponseCookie.from(cookieName, token != null ? token : "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(Duration.ofMillis(expirationMs))
                .build();
    }

    public ResponseCookie createLogoutCookie() {
        return ResponseCookie.from(cookieName, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path("/")
                .maxAge(0)
                .build();
    }

    public String extractTokenFromCookies(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (cookieName.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return null;
    }
}
