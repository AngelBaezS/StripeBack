package org.angel.paymentclean.infrastructure.adapters.in.rest;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.angel.paymentclean.domain.enums.InvoiceStatus;
import org.angel.paymentclean.domain.enums.Role;
import org.angel.paymentclean.domain.port.in.InvoiceUseCase;
import org.angel.paymentclean.infrastructure.adapters.in.advice.GlobalExceptionHandler;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.InvoiceSummaryResponse;
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

import java.security.Principal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class InvoiceControllerTest {

    private static final String SECRET = "mi_clave_secreta_super_segura_de_al_menos_32_caracteres_123456";

    @Mock
    private InvoiceUseCase invoiceUseCase;

    private JwtUtil jwtUtil;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtUtil = new JwtUtil(SECRET, 60_000L);
        InvoiceController controller = new InvoiceController(invoiceUseCase);
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
    void issueInvoiceEndpoint_shouldReturnStampedInvoiceWhenPayloadIsValid() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.CLIENT);
        InvoiceSummaryResponse summary = new InvoiceSummaryResponse(
                "invo_1", "ord_10", InvoiceStatus.STAMPED, "inv_cfdi", "UUID-123",
                "XIA190128J61", "XENON SA", "01160", "601", "G03", "facturas@xenon.mx",
                "04", true, true, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(invoiceUseCase.issueInvoiceForOrder(eq("angel"), eq("ord_10"), any())).thenReturn(summary);

        mockMvc.perform(post("/api/v1/invoices/order/ord_10/issue")
                        .cookie(new Cookie("auth_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rfc": "XIA190128J61",
                                  "legalName": "XENON SA",
                                  "zipCode": "01160",
                                  "taxRegime": "601",
                                  "cfdiUse": "G03",
                                  "email": "facturas@xenon.mx"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STAMPED"))
                .andExpect(jsonPath("$.uuid").value("UUID-123"));
    }

    @Test
    void issueInvoiceEndpoint_withInvalidRfc_shouldReturn400() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.CLIENT);

        mockMvc.perform(post("/api/v1/invoices/order/ord_10/issue")
                        .cookie(new Cookie("auth_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rfc": "INVALIDO",
                                  "legalName": "XENON SA",
                                  "zipCode": "01160",
                                  "taxRegime": "601",
                                  "cfdiUse": "G03",
                                  "email": "facturas@xenon.mx"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST_PARAMETERS"));
    }

    @Test
    void downloadInvoicePdfEndpoint_shouldReturnPdfAttachment() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.CLIENT);
        when(invoiceUseCase.downloadInvoicePdf("angel", "ord_10")).thenReturn("%PDF-1.4\n".getBytes());

        mockMvc.perform(get("/api/v1/invoices/order/ord_10/pdf")
                        .cookie(new Cookie("auth_token", token)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("factura-ord_10.pdf")));
    }

    @Test
    void resendInvoiceEmailEndpoint_shouldReturn200WithEmailSentFlag() throws Exception {
        String token = jwtUtil.generateToken("angel", Role.CLIENT);
        InvoiceSummaryResponse summary = new InvoiceSummaryResponse(
                "invo_1", "ord_10", InvoiceStatus.STAMPED, "inv_cfdi", "UUID-123",
                "XIA190128J61", "XENON SA", "01160", "601", "G03", "angelbaezs1512@gmail.com",
                "04", true, true, null, LocalDateTime.now(), LocalDateTime.now(), true
        );
        when(invoiceUseCase.resendInvoiceEmail("angel", "ord_10", "angelbaezs1512@gmail.com"))
                .thenReturn(summary);

        mockMvc.perform(post("/api/v1/invoices/order/ord_10/resend-email")
                        .cookie(new Cookie("auth_token", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"angelbaezs1512@gmail.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailSent").value(true))
                .andExpect(jsonPath("$.email").value("angelbaezs1512@gmail.com"));
    }
}
