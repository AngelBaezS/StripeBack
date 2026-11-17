package org.angel.paymentclean.infrastructure.adapters.in.rest;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.angel.paymentclean.application.service.AuthUserUseCaseImpl;
import org.angel.paymentclean.domain.enums.Role;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.port.out.StripeCustomerPort;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.angel.paymentclean.infrastructure.adapters.in.advice.GlobalExceptionHandler;
import org.angel.paymentclean.infrastructure.security.JwtFilter;
import org.angel.paymentclean.infrastructure.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerRefreshTest {

    private static final String SECRET = "mi_clave_secreta_super_segura_de_al_menos_32_caracteres_123456";

    @Mock
    private UserRepositoryPort userRepository;
    @Mock
    private StripeCustomerPort stripeCustomerPort;
    @Mock
    private PasswordEncoder passwordEncoder;

    private JwtUtil jwtUtil;
    private JwtFilter jwtFilter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtUtil = new JwtUtil(SECRET, 60_000L);
        jwtFilter = new JwtFilter(jwtUtil);
        AuthUserUseCaseImpl authUserUseCase = new AuthUserUseCaseImpl(
                userRepository, stripeCustomerPort, passwordEncoder, jwtUtil
        );
        UserController controller = new UserController(authUserUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(jwtFilter)
                .build();
    }

    @Test
    void refreshEndpoint_withValidCookie_shouldSetHttpOnlySecureStrictCookieAndHideTokenFromBody() throws Exception {
        String currentToken = jwtUtil.generateToken("angel", Role.CLIENT);
        User dbUser = new User("usr_1", "angel", "angel@correo.com", "pwd", "5512345678", "cus_1", Role.ADMIN);
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(dbUser));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("auth_token", currentToken))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("X-User-Role", "ADMIN"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("auth_token="),
                        containsString("HttpOnly"),
                        containsString("Secure"),
                        containsString("SameSite=Strict")
                )))
                .andExpect(jsonPath("$.token").value(nullValue()))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.refreshed").value(true))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());
    }

    @Test
    void loginAndLogoutEndpoints_shouldSetAndClearHttpOnlySecureStrictCookie() throws Exception {
        User dbUser = new User("usr_1", "angel", "angel@correo.com", "encoded", "5512345678", "cus_1", Role.CLIENT);
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(dbUser));
        when(passwordEncoder.matches("Secret123!", "encoded")).thenReturn(true);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"angel\",\"password\":\"Secret123!\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-User-Role", "CLIENT"))
                .andExpect(header().exists("X-Token-Expires-At"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("auth_token="),
                        containsString("HttpOnly"),
                        containsString("Secure"),
                        containsString("SameSite=Strict")
                )));

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("auth_token="),
                        containsString("Max-Age=0"),
                        containsString("HttpOnly"),
                        containsString("Secure"),
                        containsString("SameSite=Strict")
                )))
                .andExpect(jsonPath("$.message").value("Sesión cerrada correctamente."));
    }

    @Test
    void refreshEndpoint_withExpiredToken_shouldReturn401TokenExpired() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        long past = System.currentTimeMillis() - 5_000L;
        String expiredToken = Jwts.builder()
                .subject("angel")
                .claim("role", "CLIENT")
                .issuedAt(new Date(past - 60_000L))
                .expiration(new Date(past))
                .signWith(key)
                .compact();

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void refreshEndpoint_withoutToken_shouldReturn401InvalidToken() throws Exception {
        mockMvc.perform(post("/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void jwtFilter_withExpiredToken_shouldTagRequestWithTokenExpiredAndLeaveUnauthenticated() throws Exception {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        long past = System.currentTimeMillis() - 5_000L;
        String expiredToken = Jwts.builder()
                .subject("angel")
                .claim("role", "CLIENT")
                .issuedAt(new Date(past - 60_000L))
                .expiration(new Date(past))
                .signWith(key)
                .compact();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        jwtFilter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("TOKEN_EXPIRED", request.getAttribute(JwtFilter.JWT_ERROR_CODE_ATTR));
    }

    @Test
    void jwtFilter_withValidHttpOnlyCookie_shouldAuthenticateRequest() throws Exception {
        String validToken = jwtUtil.generateToken("angel", Role.ADMIN);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("auth_token", validToken));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        jwtFilter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("angel", SecurityContextHolder.getContext().getAuthentication().getName());
    }
}

