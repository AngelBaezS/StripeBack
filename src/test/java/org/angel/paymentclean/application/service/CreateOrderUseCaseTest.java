package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.enums.CartStatus;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.exception.UserNotFoundException;
import org.angel.paymentclean.domain.model.Cart;
import org.angel.paymentclean.domain.model.CartItem;
import org.angel.paymentclean.domain.model.ItemOrden;
import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.model.SpeiPaymentDetails;
import org.angel.paymentclean.domain.model.StripePaymentResult;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.port.out.CartRepositoryPort;
import org.angel.paymentclean.domain.port.out.OrderRepositoryPort;
import org.angel.paymentclean.domain.port.out.ProductRepositoryPort;
import org.angel.paymentclean.domain.port.out.StripeCustomerPort;
import org.angel.paymentclean.domain.port.out.StripePaymentPort;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateOrderUseCaseTest {

    @Mock
    private OrderRepositoryPort orderRepository;

    @Mock
    private UserRepositoryPort userRepository;

    @Mock
    private CartRepositoryPort cartRepository;

    @Mock
    private ProductRepositoryPort productRepository;

    @Mock
    private StripePaymentPort stripePayment;

    @Mock
    private StripeCustomerPort stripeCustomerPort;

    @Mock
    private SpeiInstructionEmailService speiInstructionEmailService;

    private final SpeiVoucherPdfGenerator speiVoucherPdfGenerator = new SpeiVoucherPdfGenerator();

    private CreateOrderUseCaseImpl createOrderUseCase;

    private final String userId = "usr_10";
    private final String username = "testuser";
    private final String cartId = "crt_1";
    private final User testUser = new User(userId, username, "test@user.com", "pass", "123456", "cus_test_123");

    @BeforeEach
    void setUp() {
        createOrderUseCase = new CreateOrderUseCaseImpl(
                orderRepository,
                userRepository,
                cartRepository,
                productRepository,
                stripePayment,
                stripeCustomerPort,
                speiVoucherPdfGenerator,
                speiInstructionEmailService
        );
    }

    @Test
    void createOrderWithDirectPayment_shouldLockCartAndReturnCheckoutSessionElements() {
        CartItem item1 = new CartItem("cit_1", "prd_101", 2);
        CartItem item2 = new CartItem("cit_2", "prd_102", 1);
        Cart cart = new Cart(cartId, userId, CartStatus.ACTIVE, LocalDateTime.now(), List.of(item1, item2));

        Product prod1 = new Product("prd_101", "Laptop", "Tech laptop", 10, true, "prod_1", "price_1", 150000L, "mxn"); // 2 * 150000 = 300000
        Product prod2 = new Product("prd_102", "Mouse", "Tech mouse", 5, true, "prod_2", "price_2", 5000L, "mxn");     // 1 * 5000   = 5000

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(productRepository.findById("prd_101")).thenReturn(Optional.of(prod1));
        when(productRepository.findById("prd_102")).thenReturn(Optional.of(prod2));

        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StripePaymentResult expectedResult = new StripePaymentResult("cs_elements_999", "cs_elements_999_secret_abc", null, Status.PENDING);
        when(stripePayment.crearCheckoutSessionElementsParaOrden(anyString(), eq(testUser.stripeCustomerId()), anyList()))
                .thenReturn(expectedResult);

        StripePaymentResult result = createOrderUseCase.createOrderWithDirectPayment(username);

        assertNotNull(result);
        assertEquals("cs_elements_999", result.stripeId());
        assertEquals("cs_elements_999_secret_abc", result.clientSecret());
        assertNotNull(result.orderId());

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(orderCaptor.capture());
        assertEquals("cs_elements_999", orderCaptor.getAllValues().get(1).stripCheckoutSessionId());
        assertNull(orderCaptor.getAllValues().get(1).stripePaymentIntentId());

        // Verify cart was locked to CartStatus.LOCKED
        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(cartCaptor.capture());
        assertEquals(CartStatus.LOCKED, cartCaptor.getValue().status());
        assertEquals(2, cartCaptor.getValue().items().size());
    }

    @Test
    void getOrRetryOrderPayment_whenOpenCheckoutSessionExists_shouldReuseClientSecret() {
        Order pendingOrder = new Order(
                "ord_retry_1", userId, cartId, "cus_test_123", null, "cs_open_1", null,
                null, 150000L, "mxn", Status.PENDING, List.of(), LocalDateTime.now().minusMinutes(15), null
        );
        StripePaymentResult openSession = new StripePaymentResult(
                "cs_open_1", "cs_open_1_secret_xyz", null, Status.PENDING
        );

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findById("ord_retry_1")).thenReturn(Optional.of(pendingOrder));
        when(stripePayment.recuperarCheckoutSession("cs_open_1")).thenReturn(openSession);

        StripePaymentResult result = createOrderUseCase.getOrRetryOrderPayment(username, "ord_retry_1");

        assertEquals("cs_open_1", result.stripeId());
        assertEquals("cs_open_1_secret_xyz", result.clientSecret());
        assertEquals("ord_retry_1", result.orderId());
        verify(stripePayment, never()).crearCheckoutSessionElementsParaOrden(any(), any(), any());
    }

    @Test
    void getOrRetryOrderPayment_whenPreviousSessionHasNoClientSecret_shouldExpireOldAndCreateElementsSession() {
        ItemOrden item = new ItemOrden("prd_101", "price_1", 1L);
        Order pendingOrder = new Order(
                "ord_retry_2", userId, cartId, "cus_test_123", "pi_legacy_1", "cs_hosted_1", null,
                "https://checkout.stripe.com/pay/cs_hosted_1", 150000L, "mxn", Status.PENDING,
                List.of(item), LocalDateTime.now().minusMinutes(20), null
        );
        StripePaymentResult hostedSession = new StripePaymentResult(
                "cs_hosted_1", null, "https://checkout.stripe.com/pay/cs_hosted_1", Status.PENDING
        );
        StripePaymentResult newElementsSession = new StripePaymentResult(
                "cs_elements_new", "cs_elements_new_secret", null, Status.PENDING
        );

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findById("ord_retry_2")).thenReturn(Optional.of(pendingOrder));
        when(stripePayment.recuperarCheckoutSession("cs_hosted_1")).thenReturn(hostedSession);
        when(stripePayment.crearCheckoutSessionElementsParaOrden("ord_retry_2", "cus_test_123", List.of(item)))
                .thenReturn(newElementsSession);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StripePaymentResult result = createOrderUseCase.getOrRetryOrderPayment(username, "ord_retry_2");

        assertEquals("cs_elements_new", result.stripeId());
        assertEquals("cs_elements_new_secret", result.clientSecret());
        assertEquals("ord_retry_2", result.orderId());
        verify(stripePayment).cancelarCheckoutSession("cs_hosted_1");
        verify(stripePayment).cancelarPaymentIntent("pi_legacy_1");
    }

    @Test
    void createOrderWithCheckoutLink_shouldLockCartAndReturnCheckoutSession() {
        CartItem item = new CartItem("cit_1", "prd_101", 3);
        Cart cart = new Cart(cartId, userId, CartStatus.ACTIVE, LocalDateTime.now(), List.of(item));

        Product prod = new Product("prd_101", "Teclado", "Mecánico", 10, true, "prod_k", "price_k", 25000L, "mxn");

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(productRepository.findById("prd_101")).thenReturn(Optional.of(prod));

        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StripePaymentResult expectedResult = new StripePaymentResult("cs_checkout_888", null, "https://checkout.stripe.com/pay/cs_888", Status.PENDING);
        when(stripePayment.crearLinkDePagoParaOrdenPendiente(anyString(), eq(testUser.stripeCustomerId()), anyList()))
                .thenReturn(expectedResult);

        StripePaymentResult result = createOrderUseCase.createOrderWithCheckoutLink(username);

        assertNotNull(result);
        assertEquals("cs_checkout_888", result.stripeId());
        assertEquals("https://checkout.stripe.com/pay/cs_888", result.paymentUrl());

        // Verify cart was locked to CartStatus.LOCKED
        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(cartCaptor.capture());
        assertEquals(CartStatus.LOCKED, cartCaptor.getValue().status());
    }

    @Test
    void createOrder_whenUserNotFound_shouldThrowUserNotFoundException() {
        when(userRepository.findByUsername("unknown")).thenReturn(Optional.empty());

        assertThrows(UserNotFoundException.class, () -> createOrderUseCase.createOrderWithDirectPayment("unknown"));
    }

    @Test
    void createOrder_whenActiveCartNotFound_shouldThrowArticleBusinessConflictException() {
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThrows(ArticleBusinessConflictException.class, () -> createOrderUseCase.createOrderWithDirectPayment(username));
    }

    @Test
    void createOrder_whenCartIsEmpty_shouldThrowArticleBusinessConflictException() {
        Cart emptyCart = new Cart(cartId, userId, CartStatus.ACTIVE, LocalDateTime.now(), List.of());
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(emptyCart));

        assertThrows(ArticleBusinessConflictException.class, () -> createOrderUseCase.createOrderWithDirectPayment(username));
    }

    @Test
    void createOrder_whenInsufficientStock_shouldThrowArticleBusinessConflictException() {
        CartItem item = new CartItem("cit_1", "prd_101", 15); // requests 15
        Cart cart = new Cart(cartId, userId, CartStatus.ACTIVE, LocalDateTime.now(), List.of(item));

        Product prod = new Product("prd_101", "Monitor", "4K", 5, true, "prod_m", "price_m", 400000L, "mxn"); // only 5 in stock

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(productRepository.findById("prd_101")).thenReturn(Optional.of(prod));

        assertThrows(ArticleBusinessConflictException.class, () -> createOrderUseCase.createOrderWithDirectPayment(username));
        verify(stripePayment, never()).crearCheckoutSessionElementsParaOrden(any(), any(), any());
    }

    @Test
    void createOrder_whenProductInactive_shouldThrowArticleBusinessConflictException() {
        CartItem item = new CartItem("cit_1", "prd_101", 1);
        Cart cart = new Cart(cartId, userId, CartStatus.ACTIVE, LocalDateTime.now(), List.of(item));

        Product inactiveProd = new Product("prd_101", "Monitor", "4K", 10, false, "prod_m", "price_m", 400000L, "mxn");

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(productRepository.findById("prd_101")).thenReturn(Optional.of(inactiveProd));

        assertThrows(ArticleBusinessConflictException.class, () -> createOrderUseCase.createOrderWithDirectPayment(username));
        verify(stripePayment, never()).crearCheckoutSessionElementsParaOrden(any(), any(), any());
    }

    @Test
    void createOrderWithDirectPayment_shouldAssignAndPreserveCreatedAt() {
        CartItem item = new CartItem("cit_1", "prd_101", 1);
        Cart cart = new Cart(cartId, userId, CartStatus.ACTIVE, LocalDateTime.now(), List.of(item));
        Product prod = new Product("prd_101", "Laptop", "Tech laptop", 10, true, "prod_1", "price_1", 150000L, "mxn");

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(productRepository.findById("prd_101")).thenReturn(Optional.of(prod));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stripePayment.crearCheckoutSessionElementsParaOrden(anyString(), eq(testUser.stripeCustomerId()), anyList()))
                .thenReturn(new StripePaymentResult("cs_123", "cs_123_sec", null, Status.PENDING));

        createOrderUseCase.createOrderWithDirectPayment(username);

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(orderCaptor.capture());
        List<Order> savedOrders = orderCaptor.getAllValues();
        assertNotNull(savedOrders.get(0).createdAt());
        assertEquals(savedOrders.get(0).createdAt(), savedOrders.get(1).createdAt());
    }

    @Test
    void getUserOrders_shouldReturnOrdersSortedByCreatedAtDescWithNullsLast() {
        LocalDateTime older = LocalDateTime.of(2026, 11, 10, 10, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 11, 13, 9, 0);

        Order legacyOrder = new Order("ord_zzzz", userId, cartId, "cus_1", null, null, null, null, 1000L, "mxn", Status.PAID, List.of());
        Order olderOrder = new Order("ord_mmmm", userId, cartId, "cus_1", null, null, null, null, 2000L, "mxn", Status.PAID, List.of(), older);
        Order newerOrder = new Order("ord_aaaa", userId, cartId, "cus_1", null, null, null, null, 3000L, "mxn", Status.PENDING, List.of(), newer);

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findByUserId(userId)).thenReturn(List.of(legacyOrder, olderOrder, newerOrder));

        var responses = createOrderUseCase.getUserOrders(username);

        assertEquals(3, responses.size());
        assertEquals("ord_aaaa", responses.get(0).id());
        assertEquals(newer, responses.get(0).createdAt());
        assertEquals("ord_mmmm", responses.get(1).id());
        assertEquals(older, responses.get(1).createdAt());
        assertEquals("ord_zzzz", responses.get(2).id());
        assertNull(responses.get(2).createdAt());
    }

    @Test
    void createOrderWithSpeiPayment_shouldLockCartAndReturnSpeiCheckoutSession() {
        CartItem item = new CartItem("cit_1", "prd_101", 1);
        Cart cart = new Cart(cartId, userId, CartStatus.ACTIVE, LocalDateTime.now(), List.of(item));
        Product prod = new Product("prd_101", "Laptop", "Tech laptop", 10, true, "prod_1", "price_1", 150000L, "mxn");

        StripePaymentResult expectedSession = new StripePaymentResult(
                "cs_spei_123",
                "cs_spei_123_secret_abc",
                null,
                Status.PENDING
        );

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(productRepository.findById("prd_101")).thenReturn(Optional.of(prod));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stripePayment.crearCheckoutSessionSpeiParaOrden(anyString(), eq(testUser.stripeCustomerId()), eq(testUser.email()), anyList()))
                .thenReturn(expectedSession);

        StripePaymentResult result = createOrderUseCase.createOrderWithSpeiPayment(username);

        assertNotNull(result);
        assertEquals("cs_spei_123", result.stripeId());
        assertEquals("cs_spei_123_secret_abc", result.clientSecret());
        assertNotNull(result.orderId());

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(orderCaptor.capture());
        assertEquals("cs_spei_123", orderCaptor.getAllValues().get(1).stripCheckoutSessionId());

        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(cartCaptor.capture());
        assertEquals(CartStatus.LOCKED, cartCaptor.getValue().status());
    }

    @Test
    void getUserOrders_whenPendingOrderIsOlderThan48Hours_shouldCancelOrderAndCart() {
        LocalDateTime created49HoursAgo = LocalDateTime.now().minusHours(49);
        Order expiredPending = new Order(
                "ord_exp_1", userId, cartId, "cus_1", "pi_exp_1", null, null, null,
                150000L, "mxn", Status.PENDING, List.of(), created49HoursAgo, null
        );
        Cart lockedCart = new Cart(cartId, userId, CartStatus.LOCKED, created49HoursAgo, List.of());

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findByUserId(userId)).thenReturn(List.of(expiredPending));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(lockedCart));

        var responses = createOrderUseCase.getUserOrders(username);

        assertEquals(1, responses.size());
        assertEquals(Status.CANCELED, responses.get(0).status());
        assertNotNull(responses.get(0).canceledAt());
        assertEquals(created49HoursAgo.plusHours(48), responses.get(0).expiresAt());

        verify(stripePayment).cancelarPaymentIntent("pi_exp_1");
        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(cartCaptor.capture());
        assertEquals(CartStatus.CANCELED, cartCaptor.getValue().status());
    }

    @Test
    void createOrGetOrderSpeiPayment_whenOrderIsExpired_shouldCancelAndThrowConflict() {
        LocalDateTime created50HoursAgo = LocalDateTime.now().minusHours(50);
        Order expiredPending = new Order(
                "ord_exp_2", userId, cartId, "cus_1", "pi_exp_2", null, null, null,
                150000L, "mxn", Status.PENDING, List.of(), created50HoursAgo, null
        );
        Cart lockedCart = new Cart(cartId, userId, CartStatus.LOCKED, created50HoursAgo, List.of());

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findById("ord_exp_2")).thenReturn(Optional.of(expiredPending));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(lockedCart));

        assertThrows(ArticleBusinessConflictException.class,
                () -> createOrderUseCase.createOrGetOrderSpeiPayment(username, "ord_exp_2"));
        verify(stripePayment).cancelarPaymentIntent("pi_exp_2");
    }

    @Test
    void cancelExpiredPendingOrders_shouldCancelAllOrdersOlderThan48Hours() {
        LocalDateTime created49HoursAgo = LocalDateTime.now().minusHours(49);
        Order expired = new Order(
                "ord_exp_3", userId, cartId, "cus_1", "pi_exp_3", null, null, null,
                10000L, "mxn", Status.PENDING, List.of(), created49HoursAgo, null
        );
        Cart lockedCart = new Cart(cartId, userId, CartStatus.LOCKED, created49HoursAgo, List.of());

        when(orderRepository.findPendingOrdersCreatedBefore(any(LocalDateTime.class))).thenReturn(List.of(expired));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(lockedCart));

        int canceled = createOrderUseCase.cancelExpiredPendingOrders();

        assertEquals(1, canceled);
        verify(stripePayment).cancelarPaymentIntent("pi_exp_3");
    }

    @Test
    void createOrderWithSpeiPayment_withCustomEmail_shouldForwardEmailToCheckoutSession() {
        CartItem item = new CartItem("cit_1", "prd_101", 1);
        Cart cart = new Cart(cartId, userId, CartStatus.ACTIVE, LocalDateTime.now(), List.of(item));
        Product prod = new Product("prd_101", "Laptop", "Tech laptop", 10, true, "prod_1", "price_1", 150000L, "mxn");
        String customEmail = "finanzas@empresa.mx";

        StripePaymentResult stripeResult = new StripePaymentResult(
                "cs_spei_custom",
                "cs_spei_custom_secret",
                null,
                Status.PENDING
        );

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(cart));
        when(productRepository.findById("prd_101")).thenReturn(Optional.of(prod));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(stripePayment.crearCheckoutSessionSpeiParaOrden(anyString(), eq(testUser.stripeCustomerId()), eq(customEmail), anyList()))
                .thenReturn(stripeResult);

        StripePaymentResult result = createOrderUseCase.createOrderWithSpeiPayment(username, customEmail);

        assertEquals("cs_spei_custom", result.stripeId());
        assertEquals("cs_spei_custom_secret", result.clientSecret());
        assertNotNull(result.orderId());
        verify(stripePayment).crearCheckoutSessionSpeiParaOrden(eq(result.orderId()), eq(testUser.stripeCustomerId()), eq(customEmail), anyList());
    }

    @Test
    void createOrGetOrderSpeiPayment_whenConfirmedSessionHasSpeiDetails_shouldEnrichDetailsAndSendInstructions() {
        String customEmail = "finanzas@empresa.mx";
        Order pendingOrder = new Order(
                "ord_spei_session_1", userId, cartId, "cus_stripe_123", null, "cs_spei_confirmed", null, null,
                150000L, "mxn", Status.PENDING, List.of(new ItemOrden("prd_101", "price_1", 1L)), LocalDateTime.now(), null
        );
        Product prod = new Product("prd_101", "Laptop", "Tech laptop", 10, true, "prod_1", "price_1", 150000L, "mxn");

        SpeiPaymentDetails speiDetails = new SpeiPaymentDetails(
                "646180111812345678",
                "STP",
                "646",
                "REF99999",
                "https://payments.stripe.com/bank_transfer/instructions_test",
                150000L,
                "mxn"
        );
        StripePaymentResult sessionWithSpei = new StripePaymentResult(
                "cs_spei_confirmed",
                "cs_spei_confirmed_secret",
                speiDetails.hostedInstructionsUrl(),
                Status.PENDING,
                speiDetails
        );

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findById("ord_spei_session_1")).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(productRepository.findById("prd_101")).thenReturn(Optional.of(prod));
        when(stripePayment.recuperarCheckoutSession("cs_spei_confirmed")).thenReturn(sessionWithSpei);

        StripePaymentResult result = createOrderUseCase.createOrGetOrderSpeiPayment(username, "ord_spei_session_1", customEmail);

        assertNotNull(result.speiDetails());
        assertEquals("ord_spei_session_1", result.speiDetails().orderId());
        assertEquals(customEmail, result.speiDetails().recipientEmail());
        assertNotNull(result.speiDetails().expiresAt());

        ArgumentCaptor<byte[]> pdfCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(speiInstructionEmailService).sendSpeiInstructions(
                eq(customEmail),
                eq(username),
                eq("ord_spei_session_1"),
                any(SpeiPaymentDetails.class),
                any(LocalDateTime.class),
                pdfCaptor.capture()
        );
        String pdfText = new String(pdfCaptor.getValue(), StandardCharsets.ISO_8859_1);
        assertTrue(pdfText.startsWith("%PDF-1.4"));
    }

    @Test
    void generateOrderSpeiPdf_whenPendingOrderHasSpei_shouldReturnPdfWithoutResendingEmail() {
        LocalDateTime createdAt = LocalDateTime.now().minusHours(2);
        Order pendingOrder = new Order(
                "ord_pdf_1", userId, cartId, "cus_test_123", "pi_pdf_1", null, null, null,
                150000L, "mxn", Status.PENDING, List.of(), createdAt, null
        );
        SpeiPaymentDetails speiDetails = new SpeiPaymentDetails(
                "646180111812345678",
                "STP",
                "646",
                "REFPDF1",
                "https://payments.stripe.com/bank_transfer/instructions_test",
                150000L,
                "mxn"
        );
        StripePaymentResult retrieved = new StripePaymentResult(
                "pi_pdf_1", "sec_pdf_1", speiDetails.hostedInstructionsUrl(), Status.PENDING, speiDetails
        );

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findById("ord_pdf_1")).thenReturn(Optional.of(pendingOrder));
        when(stripePayment.recuperarPaymentIntent("pi_pdf_1")).thenReturn(retrieved);

        byte[] pdfBytes = createOrderUseCase.generateOrderSpeiPdf(username, "ord_pdf_1");

        String pdfText = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        assertTrue(pdfText.startsWith("%PDF-1.4"));
        assertTrue(pdfText.contains("ord_pdf_1"));
        assertTrue(pdfText.contains("646180111812345678"));
        verifyNoInteractions(speiInstructionEmailService);
    }

    @Test
    void generateOrderSpeiPdf_whenOrderAlreadyPaid_shouldThrowConflict() {
        Order paidOrder = new Order(
                "ord_paid_1", userId, cartId, "cus_test_123", "pi_paid_1", null, null, null,
                150000L, "mxn", Status.PAID, List.of(), LocalDateTime.now().minusHours(1), null
        );
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findById("ord_paid_1")).thenReturn(Optional.of(paidOrder));

        assertThrows(ArticleBusinessConflictException.class,
                () -> createOrderUseCase.generateOrderSpeiPdf(username, "ord_paid_1"));
    }

    @Test
    void getUserOrdersAndPdf_whenOrderHasDuplicateItemRowsMatchingSingleTotal_shouldDeduplicateProductWithoutSumming() {
        Product logitech = new Product("prd_g502", "Logitech G502", "Mouse", 10, true, "prod_g", "price_g", 80000L, "mxn");
        ItemOrden duplicatedRow1 = new ItemOrden("prd_g502", "price_g", 1L);
        ItemOrden duplicatedRow2 = new ItemOrden("prd_g502", "price_g", 1L);
        Order orderWithDupRows = new Order(
                "ord_dup_1", userId, cartId, "cus_test_123", "pi_dup_1", null, null, null,
                80000L, "mxn", Status.PENDING, List.of(duplicatedRow1, duplicatedRow2),
                LocalDateTime.now().minusHours(1), null
        );
        SpeiPaymentDetails spei = new SpeiPaymentDetails(
                "646180111812345678", "STP", "646", "REFDUP1", null, 80000L, "mxn"
        );

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findByUserId(userId)).thenReturn(List.of(orderWithDupRows));
        when(orderRepository.findById("ord_dup_1")).thenReturn(Optional.of(orderWithDupRows));
        when(productRepository.findById("prd_g502")).thenReturn(Optional.of(logitech));
        when(stripePayment.recuperarPaymentIntent("pi_dup_1"))
                .thenReturn(new StripePaymentResult("pi_dup_1", "sec", null, Status.PENDING, spei));

        var orders = createOrderUseCase.getUserOrders(username);
        assertEquals(1, orders.get(0).items().size());
        assertEquals(1L, orders.get(0).items().get(0).quantity());
        assertEquals(80000L, orders.get(0).items().get(0).subtotalCents());

        byte[] pdfBytes = createOrderUseCase.generateOrderSpeiPdf(username, "ord_dup_1");
        String pdfText = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        int occurrences = pdfText.split("Logitech G502", -1).length - 1;
        assertEquals(1, occurrences, "El producto debe aparecer solo 1 vez en el PDF");
        assertTrue(pdfText.contains("(Total) Tj"));
        assertTrue(pdfText.contains("$800.00 MXN"));
    }

    @Test
    void cancelOrder_whenPending_shouldCancelStripePaymentAndCheckoutAndMarkOrderAndCartCanceled() {
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(30);
        Order pendingOrder = new Order(
                "ord_cancel_ok", userId, cartId, "cus_test_123", "pi_cancel_ok", "cs_cancel_ok", null,
                "https://checkout.stripe.com/pay/cs_cancel_ok", 150000L, "mxn", Status.PENDING, List.of(), createdAt, null
        );
        Cart lockedCart = new Cart(cartId, userId, CartStatus.LOCKED, createdAt, List.of());

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findById("ord_cancel_ok")).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(lockedCart));

        var response = createOrderUseCase.cancelOrder(username, "ord_cancel_ok");

        assertEquals("ord_cancel_ok", response.id());
        assertEquals(Status.CANCELED, response.status());
        assertNotNull(response.canceledAt());

        verify(stripePayment).cancelarPaymentIntent("pi_cancel_ok");
        verify(stripePayment).cancelarCheckoutSession("cs_cancel_ok");

        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(cartCaptor.capture());
        assertEquals(CartStatus.CANCELED, cartCaptor.getValue().status());
    }

    @Test
    void cancelOrder_whenAlreadyPaid_shouldThrowArticleBusinessConflictException() {
        Order paidOrder = new Order(
                "ord_cancel_paid", userId, cartId, "cus_test_123", "pi_paid", null, null, null,
                150000L, "mxn", Status.PAID, List.of(), LocalDateTime.now().minusMinutes(10), null
        );

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findById("ord_cancel_paid")).thenReturn(Optional.of(paidOrder));

        assertThrows(ArticleBusinessConflictException.class,
                () -> createOrderUseCase.cancelOrder(username, "ord_cancel_paid"));
        verify(stripePayment, never()).cancelarPaymentIntent(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void cancelOrder_whenBelongsToDifferentUser_shouldThrowIllegalArgumentException() {
        Order otherUserOrder = new Order(
                "ord_other", "usr_999", cartId, "cus_other", "pi_other", null, null, null,
                150000L, "mxn", Status.PENDING, List.of(), LocalDateTime.now().minusMinutes(10), null
        );

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findById("ord_other")).thenReturn(Optional.of(otherUserOrder));

        assertThrows(IllegalArgumentException.class,
                () -> createOrderUseCase.cancelOrder(username, "ord_other"));
        verify(stripePayment, never()).cancelarPaymentIntent(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createOrderWithSpeiPayment_whenStripeFails_shouldMarkOrderFailedAndRestoreCart() {
        CartItem item = new CartItem("cit_spei_err", "prd_101", 1);
        Cart activeCart = new Cart(cartId, userId, CartStatus.ACTIVE, LocalDateTime.now(), List.of(item));
        Cart lockedCart = new Cart(cartId, userId, CartStatus.LOCKED, LocalDateTime.now(), List.of(item));
        Product prod = new Product("prd_101", "Laptop", "Tech laptop", 10, true, "prod_1", "price_1", 150000L, "mxn");

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(cartRepository.findByUserIdAndStatus(userId, CartStatus.ACTIVE)).thenReturn(Optional.of(activeCart));
        when(productRepository.findById("prd_101")).thenReturn(Optional.of(prod));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cartRepository.findById(cartId)).thenReturn(Optional.of(lockedCart));
        when(stripePayment.crearCheckoutSessionSpeiParaOrden(anyString(), eq(testUser.stripeCustomerId()), any(), anyList()))
                .thenThrow(new org.angel.paymentclean.domain.exception.StripeIntegrationException("Error Stripe SPEI"));

        assertThrows(org.angel.paymentclean.domain.exception.StripeIntegrationException.class,
                () -> createOrderUseCase.createOrderWithSpeiPayment(username, "test@user.com"));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository, times(2)).save(orderCaptor.capture());
        assertEquals(Status.PENDING, orderCaptor.getAllValues().get(0).status());
        assertEquals(Status.FAILED, orderCaptor.getAllValues().get(1).status());

        ArgumentCaptor<Cart> cartCaptor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository, times(2)).save(cartCaptor.capture());
        assertEquals(CartStatus.LOCKED, cartCaptor.getAllValues().get(0).status());
        assertEquals(CartStatus.ACTIVE, cartCaptor.getAllValues().get(1).status());
    }

    @Test
    void createOrGetOrderCheckoutLink_whenPendingOrderExists_shouldExpirePreviousSessionAndReturnNewLink() {
        ItemOrden item = new ItemOrden("prd_101", "price_1", 1L);
        Order pendingOrder = new Order(
                "ord_link_1", userId, cartId, "cus_test_123", "pi_old_1", "cs_old_1", null,
                "https://checkout.stripe.com/c/pay/old", 150000L, "mxn", Status.PENDING,
                List.of(item), LocalDateTime.now().minusMinutes(10), null
        );
        StripePaymentResult freshLink = new StripePaymentResult(
                "cs_hosted_new", null, "https://checkout.stripe.com/c/pay/cs_hosted_new", Status.PENDING
        );

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findById("ord_link_1")).thenReturn(Optional.of(pendingOrder));
        when(stripePayment.crearLinkDePagoParaOrdenPendiente("ord_link_1", "cus_test_123", List.of(item)))
                .thenReturn(freshLink);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StripePaymentResult result = createOrderUseCase.createOrGetOrderCheckoutLink(username, "ord_link_1");

        assertEquals("cs_hosted_new", result.stripeId());
        assertEquals("https://checkout.stripe.com/c/pay/cs_hosted_new", result.paymentUrl());
        assertEquals("ord_link_1", result.orderId());
        verify(stripePayment).cancelarCheckoutSession("cs_old_1");
        verify(stripePayment).cancelarPaymentIntent("pi_old_1");
    }

    @Test
    void createOrGetOrderSpeiPayment_whenSessionAlreadyPaidInStripe_shouldReturnPaidWithoutCanceling() {
        Order pendingOrder = new Order(
                "ord_paid_hosted_1", userId, cartId, "cus_test_123", null, "cs_paid_1", null,
                "https://checkout.stripe.com/c/pay/cs_paid_1", 150000L, "mxn", Status.PENDING,
                List.of(new ItemOrden("prd_101", "price_1", 1L)), LocalDateTime.now().minusMinutes(2), null
        );
        StripePaymentResult paidSession = new StripePaymentResult(
                "cs_paid_1", null, null, Status.PAID
        );

        when(userRepository.findByUsername(username)).thenReturn(Optional.of(testUser));
        when(orderRepository.findById("ord_paid_hosted_1")).thenReturn(Optional.of(pendingOrder));
        when(stripePayment.recuperarCheckoutSession("cs_paid_1")).thenReturn(paidSession);

        StripePaymentResult result = createOrderUseCase.createOrGetOrderSpeiPayment(username, "ord_paid_hosted_1");

        assertEquals(Status.PAID, result.status());
        assertEquals("ord_paid_hosted_1", result.orderId());
        verify(stripePayment, never()).cancelarCheckoutSession(any());
        verify(stripePayment, never()).crearCheckoutSessionSpeiParaOrden(any(), any(), any(), any());
    }
}
