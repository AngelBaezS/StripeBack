package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.enums.CartStatus;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.model.Cart;
import org.angel.paymentclean.domain.model.ItemOrden;
import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.port.in.InvoiceUseCase;
import org.angel.paymentclean.domain.port.out.CartRepositoryPort;
import org.angel.paymentclean.domain.port.out.OrderRepositoryPort;
import org.angel.paymentclean.domain.port.out.ProductRepositoryPort;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProcessPaymentWebhookServiceTest {

    @Mock
    private OrderRepositoryPort orderRepository;

    @Mock
    private ProductRepositoryPort productRepository;

    @Mock
    private CartRepositoryPort cartRepository;

    private ProcessPaymentWebhookService service;

    private final String orderId = "ord_test_1";
    private final String productId = "prd_test_1";

    @BeforeEach
    void setUp() {
        service = new ProcessPaymentWebhookService(orderRepository, productRepository, cartRepository);
    }

    @Test
    void markOrderAsPaid_whenOrderIsPending_shouldUpdateToPaidAndDecrementStock() {
        ItemOrden item = new ItemOrden(productId, "price_1", 2L);
        Order pendingOrder = new Order(
                orderId, "usr_1", "crt_1", "cus_123", "pi_123", null, null, null,
                15000L, "mxn", Status.PENDING, List.of(item)
        );
        Product product = new Product(productId, "Laptop", "Gamer", 10, true, "prod_1", "price_1", 15000L, "mxn");

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        service.markOrderAsPaid(orderId, Status.PAID, "pi_123", "cus_123");

        verify(orderRepository).updateOrderStatusAndPaymentIntent(orderId, Status.PAID, "pi_123");

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(productCaptor.capture());
        assertEquals(8, productCaptor.getValue().stock()); // 10 - 2 = 8
    }

    @Test
    void markOrderAsPaid_whenOrderIsAlreadyPaid_shouldNotUpdateAgainNorDecrementStock() {
        ItemOrden item = new ItemOrden(productId, "price_1", 2L);
        Order alreadyPaidOrder = new Order(
                orderId, "usr_1", "crt_1", "cus_123", "pi_123", null, null, null,
                15000L, "mxn", Status.PAID, List.of(item)
        );

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(alreadyPaidOrder));

        service.markOrderAsPaid(orderId, Status.PAID, "pi_123", "cus_123");

        // Idempotency: verify update and product save are NEVER called again
        verify(orderRepository, never()).updateOrderStatusAndPaymentIntent(any(), any(), any());
        verify(productRepository, never()).save(any());
    }

    @Test
    void markOrderAsPaid_whenOrderNotFound_shouldNotUpdate() {
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        service.markOrderAsPaid(orderId, Status.PAID, "pi_123", "cus_123");

        verify(orderRepository, never()).updateOrderStatusAndPaymentIntent(any(), any(), any());
        verify(productRepository, never()).save(any());
    }

    @Test
    void markOrderAsPaymentFailed_whenOrderIsPending_shouldUpdateToFailed() {
        Order pendingOrder = new Order(
                orderId, "usr_1", "crt_1", "cus_123", null, null, null, null,
                15000L, "mxn", Status.PENDING, List.of()
        );

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));

        service.markOrderAsPaymentFailed(orderId, Status.FAILED, "Card declined");

        verify(orderRepository).updateOrderStatusAndPaymentIntent(orderId, Status.FAILED, null);
    }

    @Test
    void markOrderAsPaymentFailed_whenOrderIsAlreadyPaid_shouldNotOverrideToFailed() {
        Order paidOrder = new Order(
                orderId, "usr_1", "crt_1", "cus_123", "pi_123", null, null, null,
                15000L, "mxn", Status.PAID, List.of()
        );

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(paidOrder));

        service.markOrderAsPaymentFailed(orderId, Status.FAILED, "Late failure");

        // Idempotency: should never override an already paid order
        verify(orderRepository, never()).updateOrderStatusAndPaymentIntent(any(), any(), any());
    }

    @Test
    void markOrderAsPaid_whenOrderIdMissing_shouldResolveByPaymentIntentId() {
        ItemOrden item = new ItemOrden(productId, "price_1", 1L);
        Order pendingSpeiOrder = new Order(
                orderId, "usr_1", "crt_1", "cus_123", "pi_spei_999", null, null, null,
                15000L, "mxn", Status.PENDING, List.of(item)
        );
        Product product = new Product(productId, "Laptop", "Gamer", 5, true, "prod_1", "price_1", 15000L, "mxn");

        when(orderRepository.findByStripePaymentIntentId("pi_spei_999")).thenReturn(Optional.of(pendingSpeiOrder));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        service.markOrderAsPaid(null, Status.PAID, "pi_spei_999", "cus_123");

        verify(orderRepository).updateOrderStatusAndPaymentIntent(orderId, Status.PAID, "pi_spei_999");
        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(productCaptor.capture());
        assertEquals(4, productCaptor.getValue().stock());
    }

    @Test
    void markOrderAsCanceled_whenPending_shouldCancelOrderAndAssociatedCart() {
        Order pendingOrder = new Order(
                orderId, "usr_1", "crt_1", "cus_123", "pi_spei_888", null, null, null,
                15000L, "mxn", Status.PENDING, List.of(), LocalDateTime.now().minusHours(49), null
        );
        Cart lockedCart = new Cart("crt_1", "usr_1", CartStatus.LOCKED, LocalDateTime.now().minusHours(49), List.of());

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
        when(cartRepository.findById("crt_1")).thenReturn(Optional.of(lockedCart));

        service.markOrderAsCanceled(orderId, "pi_spei_888", "canceled");

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertEquals(Status.CANCELED, orderCaptor.getValue().status());
        assertNotNull(orderCaptor.getValue().canceledAt());

        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(cartCaptor.capture());
        assertEquals(CartStatus.CANCELED, cartCaptor.getValue().status());
    }

    @Test
    void markOrderAsPaid_withMailtrapAndInvoiceUseCase_shouldSendConfirmationEmailAndProcessPendingInvoice() {
        UserRepositoryPort userRepository = mock(UserRepositoryPort.class);
        MailtrapEmailService mailtrapEmailService = mock(MailtrapEmailService.class);
        InvoiceUseCase invoiceUseCase = mock(InvoiceUseCase.class);

        ProcessPaymentWebhookService fullService = new ProcessPaymentWebhookService(
                orderRepository, productRepository, cartRepository, userRepository, mailtrapEmailService, invoiceUseCase
        );

        ItemOrden item = new ItemOrden(productId, "price_1", 1L);
        Order pendingOrder = new Order(
                orderId, "usr_1", "crt_1", "cus_123", "pi_paid_ok", null, null, null,
                15000L, "mxn", Status.PENDING, List.of(item)
        );
        Product product = new Product(productId, "Laptop", "Gamer", 5, true, "prod_1", "price_1", 15000L, "mxn");
        User user = new User("usr_1", "angel", "angel@correo.mx", "pwd", "5512345678", "cus_123");

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(pendingOrder));
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
        when(userRepository.findById("usr_1")).thenReturn(Optional.of(user));
        when(invoiceUseCase.hasPendingInvoiceRequest(orderId)).thenReturn(true);

        fullService.markOrderAsPaid(orderId, Status.PAID, "pi_paid_ok", "cus_123");

        verify(orderRepository).updateOrderStatusAndPaymentIntent(orderId, Status.PAID, "pi_paid_ok");
        verify(mailtrapEmailService).sendPaymentConfirmationEmail(any(Order.class), eq(user), anyList(), eq(true));
        verify(invoiceUseCase).processAutomaticInvoiceOnPaymentConfirmed(orderId);
    }
}
