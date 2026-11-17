package org.angel.paymentclean.infrastructure.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtFilter extends OncePerRequestFilter {

    public static final String JWT_ERROR_CODE_ATTR = "jwt_error_code";
    public static final String JWT_ERROR_MESSAGE_ATTR = "jwt_error_message";

    private final JwtUtil jwtUtil;
    private final JwtCookieService jwtCookieService;

    public JwtFilter(JwtUtil jwtUtil) {
        this(jwtUtil, new JwtCookieService());
    }

    @Autowired
    public JwtFilter(JwtUtil jwtUtil, JwtCookieService jwtCookieService) {
        this.jwtUtil = jwtUtil;
        this.jwtCookieService = jwtCookieService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = resolveToken(request);

        if (token != null && !token.isBlank()) {
            try {
                String username = jwtUtil.extractUsername(token);
                String role = jwtUtil.extractRole(token);

                if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    List<SimpleGrantedAuthority> authorities = List.of(
                            new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())
                    );
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            username, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (ExpiredJwtException ex) {
                request.setAttribute(JWT_ERROR_CODE_ATTR, "TOKEN_EXPIRED");
                request.setAttribute(JWT_ERROR_MESSAGE_ATTR, "El token JWT ha expirado.");
            } catch (JwtException | IllegalArgumentException ex) {
                request.setAttribute(JWT_ERROR_CODE_ATTR, "INVALID_TOKEN");
                request.setAttribute(JWT_ERROR_MESSAGE_ATTR, "Token JWT inválido.");
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String cookieToken = jwtCookieService.extractTokenFromCookies(request);
        if (cookieToken != null && !cookieToken.isBlank()) {
            return cookieToken;
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        return null;
    }
}
