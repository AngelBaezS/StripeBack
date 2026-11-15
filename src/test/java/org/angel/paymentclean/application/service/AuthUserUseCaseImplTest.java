package org.angel.paymentclean.application.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.angel.paymentclean.domain.enums.Role;
import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.exception.InvalidProfileUpdateException;
import org.angel.paymentclean.domain.model.TokenRefreshResult;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.model.UsernameUpdateResult;
import org.angel.paymentclean.domain.port.out.StripeCustomerPort;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.angel.paymentclean.infrastructure.security.JwtTokenException;
import org.angel.paymentclean.infrastructure.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthUserUseCaseImplTest {

    private static final String SECRET = "mi_clave_secreta_super_segura_de_al_menos_32_caracteres_123456";

    @Mock
    private UserRepositoryPort userRepository;
    @Mock
    private StripeCustomerPort stripeCustomerPort;
    @Mock
    private PasswordEncoder passwordEncoder;

    private JwtUtil jwtUtil;
    private AuthUserUseCaseImpl authUseCase;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(SECRET, 60_000L);
        authUseCase = new AuthUserUseCaseImpl(userRepository, stripeCustomerPort, passwordEncoder, jwtUtil);
    }

    @Test
    void refreshToken_withValidToken_shouldReturnNewTokenAndCurrentRole() {
        String currentToken = jwtUtil.generateToken("angel", Role.CLIENT);
        User dbUser = new User("usr_123", "angel", "angel@correo.com", "encoded", "5512345678", "cus_123", Role.ADMIN);
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(dbUser));

        TokenRefreshResult result = authUseCase.refreshToken(currentToken);

        assertTrue(result.refreshed());
        assertNotNull(result.token());
        assertNotEquals(currentToken, result.token());
        assertEquals(Role.ADMIN, result.role());
        assertNotNull(result.expiresAt());
        assertEquals("ADMIN", jwtUtil.extractRole(result.token()));
    }

    @Test
    void refreshToken_withExpiredToken_shouldThrowTokenExpiredException() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        long past = System.currentTimeMillis() - 5_000L;
        String expiredToken = Jwts.builder()
                .subject("angel")
                .claim("role", "CLIENT")
                .issuedAt(new Date(past - 60_000L))
                .expiration(new Date(past))
                .signWith(key)
                .compact();

        JwtTokenException ex = assertThrows(JwtTokenException.class, () -> authUseCase.refreshToken(expiredToken));
        assertEquals("TOKEN_EXPIRED", ex.getCode());
    }

    @Test
    void refreshToken_withInvalidOrBlankToken_shouldThrowInvalidTokenException() {
        JwtTokenException blankEx = assertThrows(JwtTokenException.class, () -> authUseCase.refreshToken("  "));
        assertEquals("INVALID_TOKEN", blankEx.getCode());

        JwtTokenException invalidEx = assertThrows(JwtTokenException.class, () -> authUseCase.refreshToken("not.a.jwt"));
        assertEquals("INVALID_TOKEN", invalidEx.getCode());
    }

    @Test
    void refreshToken_whenUserNoLongerExists_shouldThrowInvalidTokenException() {
        String currentToken = jwtUtil.generateToken("ghost", Role.CLIENT);
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        JwtTokenException ex = assertThrows(JwtTokenException.class, () -> authUseCase.refreshToken(currentToken));
        assertEquals("INVALID_TOKEN", ex.getCode());
    }

    @Test
    void updateUsername_withAvailableUsername_shouldSaveUserAndReturnNewJwt() {
        User current = new User("usr_123", "angel", "angel@correo.com", "encoded", "5512345678", "cus_123", Role.ADMIN);
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(current));
        when(userRepository.findByUsername("angel_nuevo")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UsernameUpdateResult result = authUseCase.updateUsername("angel", "  angel_nuevo  ");

        assertEquals("angel_nuevo", result.user().username());
        assertEquals("angel_nuevo", jwtUtil.extractUsername(result.token()));
        assertEquals("ADMIN", jwtUtil.extractRole(result.token()));
    }

    @Test
    void updateUsername_whenTakenByAnotherUser_shouldThrowConflictException() {
        User current = new User("usr_123", "angel", "angel@correo.com", "encoded", "5512345678", "cus_123", Role.CLIENT);
        User other = new User("usr_999", "ocupado", "otro@correo.com", "encoded", "5599999999", "cus_999", Role.CLIENT);
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(current));
        when(userRepository.findByUsername("ocupado")).thenReturn(Optional.of(other));

        assertThrows(ArticleBusinessConflictException.class, () -> authUseCase.updateUsername("angel", "ocupado"));
        verify(userRepository, never()).save(any());
    }

    @Test
    void updatePassword_withValidCurrentPassword_shouldEncodeAndSaveNewPassword() {
        User current = new User("usr_123", "angel", "angel@correo.com", "encoded_old", "5512345678", "cus_123", Role.CLIENT);
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(current));
        when(passwordEncoder.matches("oldPass123", "encoded_old")).thenReturn(true);
        when(passwordEncoder.matches("newPass123", "encoded_old")).thenReturn(false);
        when(passwordEncoder.encode("newPass123")).thenReturn("encoded_new");

        authUseCase.updatePassword("angel", "oldPass123", "newPass123");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("encoded_new", captor.getValue().password());
    }

    @Test
    void updatePassword_withWrongCurrentPassword_shouldThrowInvalidProfileUpdateException() {
        User current = new User("usr_123", "angel", "angel@correo.com", "encoded_old", "5512345678", "cus_123", Role.ADMIN);
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(current));
        when(passwordEncoder.matches("wrongPass", "encoded_old")).thenReturn(false);

        InvalidProfileUpdateException ex = assertThrows(
                InvalidProfileUpdateException.class,
                () -> authUseCase.updatePassword("angel", "wrongPass", "newPass123")
        );
        assertEquals("INVALID_CURRENT_PASSWORD", ex.getCode());
        verify(userRepository, never()).save(any());
    }
}

