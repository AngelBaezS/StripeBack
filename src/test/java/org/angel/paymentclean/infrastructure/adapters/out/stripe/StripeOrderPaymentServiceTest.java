package org.angel.paymentclean.infrastructure.adapters.out.stripe;

import com.stripe.StripeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class StripeOrderPaymentServiceTest {

    private final StripeOrderPaymentService service = new StripeOrderPaymentService(mock(StripeClient.class));

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void resolvedFrontendUrl_whenRequestOriginIsAllowedTunnel_shouldReturnTunnelOrigin() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/order/payment-link");
        request.addHeader("Origin", "https://store-demo.trycloudflare.com");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("https://store-demo.trycloudflare.com", service.resolvedFrontendUrl());
    }

    @Test
    void resolvedFrontendUrl_whenForwardedHostFromTunnel_shouldReturnForwardedOrigin() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/order/ord_1/payment");
        request.addHeader("X-Forwarded-Proto", "https");
        request.addHeader("X-Forwarded-Host", "tienda-demo.lhr.life");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("https://tienda-demo.lhr.life", service.resolvedFrontendUrl());
    }

    @Test
    void resolvedFrontendUrl_whenRefererIsAllowedTunnel_shouldReturnSchemeAndHost() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/order/ord_1/payment");
        request.addHeader("Referer", "https://mi-app.ngrok-free.app/cart/checkout?orderId=ord_1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("https://mi-app.ngrok-free.app", service.resolvedFrontendUrl());
    }

    @Test
    void resolvedFrontendUrl_whenUntrustedOrigin_shouldFallbackToDefaultFrontendUrl() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/order/payment-link");
        request.addHeader("Origin", "https://evil-phishing.example.com");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("http://localhost:4200", service.resolvedFrontendUrl());
    }
}
