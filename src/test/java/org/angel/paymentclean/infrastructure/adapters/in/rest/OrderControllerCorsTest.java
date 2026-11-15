package org.angel.paymentclean.infrastructure.adapters.in.rest;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.angel.paymentclean.domain.enums.Role;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.model.SpeiPaymentDetails;
import org.angel.paymentclean.domain.model.StripePaymentResult;
import org.angel.paymentclean.domain.port.in.CreateOrderUseCase;
import org.angel.paymentclean.infrastructure.adapters.in.advice.GlobalExceptionHandler;
import org.angel.paymentclean.infrastructure.config.SecurityConfig;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.security.Principal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerCorsTest {

    private static final String SECRET = "mi_clave_secreta_super_segura_de_al_menos_32_caracteres_123456";

    @Mock
    private CreateOrderUseCase createOrderUseCase;

    private JwtUtil jwtUtil;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtUtil = new JwtUtil(SECRET, 60_000L);
        JwtFilter jwtFilter = new JwtFilter(jwtUtil);
        SecurityConfig securityConfig = new SecurityConfig(jwtFilter, mock(HandlerExceptionResolver.class));
        CorsConfigurationSource corsSource = securityConfig.corsConfigurationSource();
        CorsFilter corsFilter = securityConfig.corsFilterRegistration(corsSource).getFilter();

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

        OrderController controller = new OrderController(createOrderUseCase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(corsFilter, jwtFilter, principalBridgeFilter)
                .build();
    }

    @Test
    void preflightOptionsForSpeiPayment_shouldAllowLocalhostOriginAndCustomHeaders() throws Exception {
        mockMvc.perform(options("/api/v1/order/spei-payment")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,cache-control"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void postSpeiPayment_shouldReturn200WithCorsHeaders() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.CLIENT);
        StripePaymentResult result = new StripePaymentResult(
                "pi_spei_1",
                "pi_spei_1_secret",
                "https://payments.stripe.com/bank_transfer/test",
                Status.PENDING,
                new SpeiPaymentDetails("646180111812345678", "STP", "646", "REF123",
                        "https://payments.stripe.com/bank_transfer/test", 150000L, "mxn"),
                "ord_1"
        );
        when(createOrderUseCase.createOrderWithSpeiPayment("angel", "angel@correo.mx")).thenReturn(result);

        mockMvc.perform(post("/api/v1/order/spei-payment")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"angel@correo.mx\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
                .andExpect(jsonPath("$.stripeId").value("pi_spei_1"))
                .andExpect(jsonPath("$.speiDetails.clabe").value("646180111812345678"));
    }

    @Test
    void postSpeiPayment_whenUnexpectedErrorOccurs_shouldStillIncludeCorsHeaders() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.CLIENT);
        when(createOrderUseCase.createOrderWithSpeiPayment("angel", "angel@correo.mx"))
                .thenThrow(new RuntimeException("Fallo simulado de Stripe"));

        mockMvc.perform(post("/api/v1/order/spei-payment")
                        .header(HttpHeaders.ORIGIN, "http://localhost:4200")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"angel@correo.mx\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:4200"))
                .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
    }
}
