package org.angel.paymentclean.infrastructure.adapters.in.rest;

import jakarta.validation.Valid;
import org.angel.paymentclean.domain.model.StripePaymentResult;
import org.angel.paymentclean.domain.port.in.CreateOrderUseCase;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderDetailResponse;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.SpeiPaymentRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/order")
public class OrderController {
    private final CreateOrderUseCase createOrderUseCase;

    public OrderController(CreateOrderUseCase createOrderUseCase) {
        this.createOrderUseCase = createOrderUseCase;
    }

    @GetMapping
    public ResponseEntity<List<OrderDetailResponse>> getUserOrders(Authentication authentication) {
        return ResponseEntity.ok(createOrderUseCase.getUserOrders(authentication.getName()));
    }

    @GetMapping("/{orderId}/payment")
    public ResponseEntity<StripePaymentResult> getOrRetryOrderPayment(
            @PathVariable String orderId,
            Authentication authentication) {
        return ResponseEntity.ok(createOrderUseCase.getOrRetryOrderPayment(authentication.getName(), orderId));
    }

    @PostMapping("/direct-payment")
    public ResponseEntity<StripePaymentResult> createDirectPaymentOrder(Authentication authentication) {
        return ResponseEntity.ok(createOrderUseCase.createOrderWithDirectPayment(authentication.getName()));
    }

    @PostMapping("/spei-payment")
    public ResponseEntity<StripePaymentResult> createSpeiPaymentOrder(
            @Valid @RequestBody(required = false) SpeiPaymentRequest request,
            Authentication authentication) {
        String email = request != null ? request.email() : null;
        return ResponseEntity.ok(createOrderUseCase.createOrderWithSpeiPayment(authentication.getName(), email));
    }

    @PostMapping("/{orderId}/spei-payment")
    public ResponseEntity<StripePaymentResult> createOrGetOrderSpeiPayment(
            @PathVariable String orderId,
            @Valid @RequestBody(required = false) SpeiPaymentRequest request,
            Authentication authentication) {
        String email = request != null ? request.email() : null;
        return ResponseEntity.ok(createOrderUseCase.createOrGetOrderSpeiPayment(authentication.getName(), orderId, email));
    }

    @GetMapping(value = "/{orderId}/spei-pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadOrderSpeiPdf(
            @PathVariable String orderId,
            Authentication authentication) {
        byte[] pdfBytes = createOrderUseCase.generateOrderSpeiPdf(authentication.getName(), orderId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(
                ContentDisposition.attachment().filename("ficha-spei-" + orderId + ".pdf").build()
        );
        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }

    @PostMapping("/payment-link")
    public ResponseEntity<StripePaymentResult> createPaymentLinkOrder(Authentication authentication) {
        return ResponseEntity.ok(createOrderUseCase.createOrderWithCheckoutLink(authentication.getName()));
    }

    @PostMapping("/{orderId}/payment-link")
    public ResponseEntity<StripePaymentResult> createOrGetOrderPaymentLink(
            @PathVariable String orderId,
            Authentication authentication) {
        return ResponseEntity.ok(createOrderUseCase.createOrGetOrderCheckoutLink(authentication.getName(), orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderDetailResponse> cancelOrder(
            @PathVariable String orderId,
            Authentication authentication) {
        return ResponseEntity.ok(createOrderUseCase.cancelOrder(authentication.getName(), orderId));
    }
}
