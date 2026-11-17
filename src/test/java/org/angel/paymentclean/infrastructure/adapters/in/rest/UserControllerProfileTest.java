package org.angel.paymentclean.infrastructure.adapters.in.rest;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.Optional;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerProfileTest {

    private static final String SECRET = "mi_clave_secreta_super_segura_de_al_menos_32_caracteres_123456";

    @Mock
    private UserRepositoryPort userRepository;
    @Mock
    private StripeCustomerPort stripeCustomerPort;
    @Mock
    private PasswordEncoder passwordEncoder;

    private JwtUtil jwtUtil;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtUtil = new JwtUtil(SECRET, 60_000L);
        AuthUserUseCaseImpl authUserUseCase = new AuthUserUseCaseImpl(
                userRepository, stripeCustomerPort, passwordEncoder, jwtUtil
        );
        UserController controller = new UserController(authUserUseCase);
        Filter principalBridgeFilter = (request, response, chain) -> {
            HttpServletRequest httpReq = (HttpServletRequest) request;
            HttpServletRequest wrapped = new HttpServletRequestWrapper(httpReq) {
                @Override
                public Principal getUserPrincipal() {
                    return SecurityContextHolder.getContext().getAuthentication();
                }
            };
            chain.doFilter(wrapped, response);
        };
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new JwtFilter(jwtUtil), principalBridgeFilter)
                .build();
    }

    @Test
    void updateUsernameEndpoint_shouldReturn200WithUpdatedProfileAndNewToken() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.ADMIN);
        User current = new User("usr_1", "angel", "angel@correo.com", "pwd", "5512345678", "cus_1", Role.ADMIN);
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(current));
        when(userRepository.findByUsername("angel_admin")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put("/api/v1/auth/me/username")
                        .cookie(new Cookie("auth_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"angel_admin\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-User-Role", "ADMIN"))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
                        containsString("auth_token="),
                        containsString("HttpOnly"),
                        containsString("Secure"),
                        containsString("SameSite=Strict")
                )))
                .andExpect(jsonPath("$.profile.username").value("angel_admin"))
                .andExpect(jsonPath("$.profile.role").value("ADMIN"))
                .andExpect(jsonPath("$.token").value(nullValue()));
    }

    @Test
    void updateUsernameEndpoint_whenDuplicate_shouldReturn409Conflict() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.CLIENT);
        User current = new User("usr_1", "angel", "angel@correo.com", "pwd", "5512345678", "cus_1", Role.CLIENT);
        User other = new User("usr_2", "tomado", "otro@correo.com", "pwd", "5500000000", "cus_2", Role.CLIENT);
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(current));
        when(userRepository.findByUsername("tomado")).thenReturn(Optional.of(other));

        mockMvc.perform(put("/api/v1/auth/me/username")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tomado\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void updatePasswordEndpoint_whenWrongCurrentPassword_shouldReturn400BadRequest() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.CLIENT);
        User current = new User("usr_1", "angel", "angel@correo.com", "encoded", "5512345678", "cus_1", Role.CLIENT);
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(current));
        when(passwordEncoder.matches("incorrecta", "encoded")).thenReturn(false);

        mockMvc.perform(put("/api/v1/auth/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"incorrecta\",\"newPassword\":\"nueva123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void updatePasswordEndpoint_withValidData_shouldReturn200() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.ADMIN);
        User current = new User("usr_1", "angel", "angel@correo.com", "encoded", "5512345678", "cus_1", Role.ADMIN);
        when(userRepository.findByUsername("angel")).thenReturn(Optional.of(current));
        when(passwordEncoder.matches("actual123", "encoded")).thenReturn(true);
        when(passwordEncoder.matches("nueva123", "encoded")).thenReturn(false);
        when(passwordEncoder.encode("nueva123")).thenReturn("encoded_new");

        mockMvc.perform(put("/api/v1/auth/me/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"actual123\",\"newPassword\":\"nueva123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Contraseña actualizada correctamente."));
    }
}
