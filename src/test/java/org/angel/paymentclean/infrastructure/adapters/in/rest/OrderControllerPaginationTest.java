package org.angel.paymentclean.infrastructure.adapters.in.rest;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.angel.paymentclean.domain.enums.Role;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.port.in.CreateOrderUseCase;
import org.angel.paymentclean.infrastructure.adapters.in.advice.GlobalExceptionHandler;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderDetailResponse;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderPageResponse;
import org.angel.paymentclean.infrastructure.security.JwtFilter;
import org.angel.paymentclean.infrastructure.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OrderControllerPaginationTest {

    private static final String SECRET = "mi_clave_secreta_super_segura_de_al_menos_32_caracteres_123456";

    @Mock
    private CreateOrderUseCase createOrderUseCase;

    private JwtUtil jwtUtil;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtUtil = new JwtUtil(SECRET, 60_000L);
        OrderController controller = new OrderController(createOrderUseCase);
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
    void getUserOrders_shouldDefaultToPage0Size10AndReturnOrderPageResponse() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.CLIENT);
        OrderDetailResponse item = new OrderDetailResponse(
                "ord_1", "usr_1", "crt_1", "pi_1", null,
                150000L, "mxn", Status.PAID, LocalDateTime.now(), null, null, List.of()
        );
        OrderPageResponse pageResponse = new OrderPageResponse(
                List.of(item), 0, 10, 1, 1, false, false, 1, 1, 0, 0
        );
        when(createOrderUseCase.getUserOrdersPaginated("angel", 0, 10, null)).thenReturn(pageResponse);

        mockMvc.perform(get("/api/v1/order")
                        .cookie(new Cookie("auth_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.content[0].id").value("ord_1"));

        verify(createOrderUseCase).getUserOrdersPaginated("angel", 0, 10, null);
    }

    @Test
    void getUserOrders_shouldForwardPageSizeAndStatusQueryParams() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.CLIENT);
        OrderPageResponse pageResponse = new OrderPageResponse(
                List.of(), 2, 10, 22, 3, false, true, 30, 22, 5, 3
        );
        when(createOrderUseCase.getUserOrdersPaginated("angel", 2, 10, Status.PAID)).thenReturn(pageResponse);

        mockMvc.perform(get("/api/v1/order")
                        .param("page", "2")
                        .param("size", "10")
                        .param("status", "PAID")
                        .cookie(new Cookie("auth_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.hasPrevious").value(true))
                .andExpect(jsonPath("$.totalPaid").value(22));

        verify(createOrderUseCase).getUserOrdersPaginated("angel", 2, 10, Status.PAID);
    }

    @Test
    void syncOrderPaymentStatus_shouldForwardOrderIdAndSessionId() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.CLIENT);
        OrderDetailResponse synced = new OrderDetailResponse(
                "ord_sync_1", "usr_1", "crt_1", "pi_sync_1", null,
                150000L, "mxn", Status.PAID, LocalDateTime.now(), null, null, List.of()
        );
        when(createOrderUseCase.syncOrderPaymentStatus("angel", "ord_sync_1", "cs_test_123"))
                .thenReturn(synced);

        mockMvc.perform(post("/api/v1/order/ord_sync_1/sync-payment")
                        .param("sessionId", "cs_test_123")
                        .cookie(new Cookie("auth_token", token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ord_sync_1"))
                .andExpect(jsonPath("$.status").value("PAID"));

        verify(createOrderUseCase).syncOrderPaymentStatus("angel", "ord_sync_1", "cs_test_123");
    }
}
