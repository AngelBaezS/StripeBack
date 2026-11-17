package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.enums.CartStatus;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.exception.ProductNotFoundException;
import org.angel.paymentclean.domain.exception.UserNotFoundException;
import org.angel.paymentclean.domain.model.Cart;
import org.angel.paymentclean.domain.model.CartItem;
import org.angel.paymentclean.domain.model.ItemOrden;
import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.model.OrderInvoice;
import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.model.SpeiPaymentDetails;
import org.angel.paymentclean.domain.model.StripePaymentResult;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.port.in.CreateOrderUseCase;
import org.angel.paymentclean.domain.port.in.InvoiceUseCase;
import org.angel.paymentclean.domain.port.in.ProcessPaymentWebhookUseCase;
import org.angel.paymentclean.domain.port.out.CartRepositoryPort;
import org.angel.paymentclean.domain.port.out.OrderInvoiceRepositoryPort;
import org.angel.paymentclean.domain.port.out.OrderRepositoryPort;
import org.angel.paymentclean.domain.port.out.ProductRepositoryPort;
import org.angel.paymentclean.domain.port.out.StripeCustomerPort;
import org.angel.paymentclean.domain.port.out.StripePaymentPort;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.angel.paymentclean.domain.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class CreateOrderUseCaseImpl implements CreateOrderUseCase {

    private static final Logger log = LoggerFactory.getLogger(CreateOrderUseCaseImpl.class);

    @Value("${app.order.expiration-hours:48}")
    private long expirationHours = 48L;

    private final OrderRepositoryPort orderRepository;
    private final UserRepositoryPort userRepository;
    private final CartRepositoryPort cartRepository;
    private final ProductRepositoryPort productRepository;
    private final StripePaymentPort stripePayment;
    private final StripeCustomerPort stripeCustomerPort;
    private final SpeiVoucherPdfGenerator speiVoucherPdfGenerator;
    private final SpeiInstructionEmailService speiInstructionEmailService;
    private final OrderInvoiceRepositoryPort orderInvoiceRepository;
    private final ObjectProvider<ProcessPaymentWebhookUseCase> processPaymentWebhookProvider;
    private final ObjectProvider<InvoiceUseCase> invoiceUseCaseProvider;
    private final TransactionTemplate requiresNewTxTemplate;

    public CreateOrderUseCaseImpl(
            OrderRepositoryPort orderRepository,
            UserRepositoryPort userRepository,
            CartRepositoryPort cartRepository,
            ProductRepositoryPort productRepository,
            StripePaymentPort stripePayment,
            StripeCustomerPort stripeCustomerPort) {
        this(orderRepository, userRepository, cartRepository, productRepository,
                stripePayment, stripeCustomerPort, new SpeiVoucherPdfGenerator(), null, null, null, null, null);
    }

    public CreateOrderUseCaseImpl(
            OrderRepositoryPort orderRepository,
            UserRepositoryPort userRepository,
            CartRepositoryPort cartRepository,
            ProductRepositoryPort productRepository,
            StripePaymentPort stripePayment,
            StripeCustomerPort stripeCustomerPort,
            SpeiVoucherPdfGenerator speiVoucherPdfGenerator,
            SpeiInstructionEmailService speiInstructionEmailService) {
        this(orderRepository, userRepository, cartRepository, productRepository,
                stripePayment, stripeCustomerPort, speiVoucherPdfGenerator, speiInstructionEmailService, null, null, null, null);
    }

    @Autowired
    public CreateOrderUseCaseImpl(
            OrderRepositoryPort orderRepository,
            UserRepositoryPort userRepository,
            CartRepositoryPort cartRepository,
            ProductRepositoryPort productRepository,
            StripePaymentPort stripePayment,
            StripeCustomerPort stripeCustomerPort,
            SpeiVoucherPdfGenerator speiVoucherPdfGenerator,
            SpeiInstructionEmailService speiInstructionEmailService,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider,
            ObjectProvider<OrderInvoiceRepositoryPort> orderInvoiceRepositoryProvider,
            ObjectProvider<ProcessPaymentWebhookUseCase> processPaymentWebhookProvider,
            ObjectProvider<InvoiceUseCase> invoiceUseCaseProvider) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.stripePayment = stripePayment;
        this.stripeCustomerPort = stripeCustomerPort;
        this.speiVoucherPdfGenerator = speiVoucherPdfGenerator != null
                ? speiVoucherPdfGenerator
                : new SpeiVoucherPdfGenerator();
        this.speiInstructionEmailService = speiInstructionEmailService;
        this.orderInvoiceRepository = orderInvoiceRepositoryProvider != null
                ? orderInvoiceRepositoryProvider.getIfAvailable()
                : null;
        this.processPaymentWebhookProvider = processPaymentWebhookProvider;
        this.invoiceUseCaseProvider = invoiceUseCaseProvider;
        PlatformTransactionManager txManager = transactionManagerProvider != null
                ? transactionManagerProvider.getIfAvailable()
                : null;
        if (txManager != null) {
            TransactionTemplate template = new TransactionTemplate(txManager);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTxTemplate = template;
        } else {
            this.requiresNewTxTemplate = null;
        }
    }

    private record PreparedOrderData(User user, long totalAmountCents, List<ItemOrden> orderItems, Cart cart) {
    }

    private record PendingOrderContext(PreparedOrderData data, Order pendingOrder, String targetEmail) {
    }

    private <T> T runInTransaction(Supplier<T> action) {
        if (requiresNewTxTemplate != null) {
            return requiresNewTxTemplate.execute(status -> action.get());
        }
        return action.get();
    }

    private PreparedOrderData prepareOrderFromActiveCart(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("El usuario autenticado es requerido.");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(UserNotFoundException::new);

        String stripeCustomerId = user.stripeCustomerId();
        if (stripeCustomerId == null || stripeCustomerId.isBlank()) {
            stripeCustomerId = stripeCustomerPort.crearClienteEnStripe(user.username(), user.email(), user.phone());
            user = new User(user.id(), user.username(), user.email(), user.password(), user.phone(), stripeCustomerId, user.role());
            userRepository.save(user);
        }

        Cart cart = cartRepository.findByUserIdAndStatus(user.id(), CartStatus.ACTIVE)
                .orElseThrow(() -> new ArticleBusinessConflictException("No existe un carrito activo para realizar la compra."));

        if (cart.items() == null || cart.items().isEmpty()) {
            throw new ArticleBusinessConflictException("El carrito no contiene artículos para generar una orden.");
        }

        long totalAmountCents = 0L;
        List<ItemOrden> orderItems = new ArrayList<>();

        for (CartItem cartItem : cart.items()) {
            Product product = productRepository.findById(cartItem.productId())
                    .orElseThrow(() -> new ProductNotFoundException(cartItem.productId()));

            if (Boolean.FALSE.equals(product.active())) {
                throw new ArticleBusinessConflictException(
                        "El producto '" + product.name() + "' no se encuentra activo.");
            }

            if (product.stock() == null || product.stock() < cartItem.quantity()) {
                int availableStock = product.stock() == null ? 0 : product.stock();
                throw new ArticleBusinessConflictException("Stock insuficiente para el producto '" + product.name()
                        + "'. Disponible: " + availableStock + ", Solicitado: " + cartItem.quantity());
            }

            if (product.stripePriceId() == null || product.stripePriceId().isBlank()) {
                throw new ArticleBusinessConflictException(
                        "El producto '" + product.name() + "' no tiene un precio de Stripe configurado.");
            }

            if (product.unitAmountCents() == null || product.unitAmountCents() <= 0) {
                throw new ArticleBusinessConflictException(
                        "El producto '" + product.name() + "' tiene un precio inválido.");
            }

            totalAmountCents += product.unitAmountCents() * cartItem.quantity();
            orderItems.add(new ItemOrden(product.id(), product.stripePriceId(), (long) cartItem.quantity()));
        }

        // Bloquear el carrito: pasa de ACTIVE a LOCKED para congelar su estado
        Cart lockedCart = new Cart(
                cart.id(),
                cart.userId(),
                CartStatus.LOCKED,
                LocalDateTime.now(),
                cart.items()
        );
        cartRepository.save(lockedCart);

        return new PreparedOrderData(user, totalAmountCents, orderItems, lockedCart);
    }

    private PendingOrderContext createAndCommitPendingOrder(String username, String recipientEmail) {
        return runInTransaction(() -> {
            PreparedOrderData data = prepareOrderFromActiveCart(username);
            String orderId = IdGenerator.orderId();
            LocalDateTime createdAt = LocalDateTime.now();
            String targetEmail = resolveRecipientEmail(recipientEmail, data.user());

            Order pendingOrder = orderRepository.save(new Order(
                    orderId, data.user().id(), data.cart().id(), data.user().stripeCustomerId(), null, null, null, null,
                    data.totalAmountCents(), "mxn", Status.PENDING, data.orderItems(), createdAt, null
            ));
            return new PendingOrderContext(data, pendingOrder, targetEmail);
        });
    }

    private void markPendingOrderFailedAndRestoreCart(PendingOrderContext context) {
        runInTransaction(() -> {
            Order pendingOrder = context.pendingOrder();
            orderRepository.save(new Order(
                    pendingOrder.id(), pendingOrder.userId(), pendingOrder.cartId(), pendingOrder.stripeCustomerId(),
                    pendingOrder.stripePaymentIntentId(), pendingOrder.stripCheckoutSessionId(),
                    pendingOrder.stripePaymentLinkId(), pendingOrder.paymentUrl(),
                    pendingOrder.totalAmountCents(), pendingOrder.currency(), Status.FAILED,
                    pendingOrder.items(), pendingOrder.createdAt(), pendingOrder.canceledAt()
            ));

            Cart lockedCart = context.data().cart();
            if (lockedCart != null && lockedCart.id() != null) {
                cartRepository.findById(lockedCart.id()).ifPresentOrElse(
                        existingCart -> {
                            if (existingCart.status() == CartStatus.LOCKED) {
                                cartRepository.save(new Cart(
                                        existingCart.id(),
                                        existingCart.userId(),
                                        CartStatus.ACTIVE,
                                        LocalDateTime.now(),
                                        existingCart.items()
                                ));
                            }
                        },
                        () -> cartRepository.save(new Cart(
                                lockedCart.id(),
                                lockedCart.userId(),
                                CartStatus.ACTIVE,
                                LocalDateTime.now(),
                                lockedCart.items()
                        ))
                );
            }
            return null;
        });
    }

    @Override
    public StripePaymentResult createOrderWithDirectPayment(String username) {
        PendingOrderContext ctx = createAndCommitPendingOrder(username, null);
        Order pendingOrder = ctx.pendingOrder();

        StripePaymentResult csResult;
        try {
            csResult = stripePayment.crearCheckoutSessionElementsParaOrden(
                    pendingOrder.id(), ctx.data().user().stripeCustomerId(), ctx.data().orderItems()
            );
        } catch (RuntimeException ex) {
            markPendingOrderFailedAndRestoreCart(ctx);
            throw ex;
        }

        runInTransaction(() -> orderRepository.save(new Order(
                pendingOrder.id(), ctx.data().user().id(), ctx.data().cart().id(), ctx.data().user().stripeCustomerId(),
                null, csResult.stripeId(), null, null,
                ctx.data().totalAmountCents(), "mxn", csResult.status(), ctx.data().orderItems(), pendingOrder.createdAt()
        )));

        return csResult.withOrderId(pendingOrder.id());
    }

    @Override
    public StripePaymentResult createOrderWithSpeiPayment(String username) {
        return createOrderWithSpeiPayment(username, null);
    }

    @Override
    public StripePaymentResult createOrderWithSpeiPayment(String username, String recipientEmail) {
        PendingOrderContext ctx = createAndCommitPendingOrder(username, recipientEmail);
        Order pendingOrder = ctx.pendingOrder();

        StripePaymentResult speiSession;
        try {
            speiSession = stripePayment.crearCheckoutSessionSpeiParaOrden(
                    pendingOrder.id(),
                    ctx.data().user().stripeCustomerId(),
                    ctx.targetEmail(),
                    ctx.data().orderItems()
            );
        } catch (RuntimeException ex) {
            markPendingOrderFailedAndRestoreCart(ctx);
            throw ex;
        }

        Order updatedOrder = runInTransaction(() -> orderRepository.save(new Order(
                pendingOrder.id(), ctx.data().user().id(), ctx.data().cart().id(), ctx.data().user().stripeCustomerId(),
                null, speiSession.stripeId(), null, speiSession.paymentUrl(),
                ctx.data().totalAmountCents(), "mxn", speiSession.status(), ctx.data().orderItems(), pendingOrder.createdAt(), null
        )));

        if (speiSession.speiDetails() != null) {
            return finalizeSpeiResultAndNotify(speiSession, updatedOrder, ctx.data().user(), ctx.targetEmail(), true);
        }
        return speiSession.withOrderId(updatedOrder.id());
    }

    @Override
    public StripePaymentResult createOrderWithCheckoutLink(String username) {
        PendingOrderContext ctx = createAndCommitPendingOrder(username, null);
        Order pendingOrder = ctx.pendingOrder();

        StripePaymentResult sessionResult;
        try {
            sessionResult = stripePayment.crearLinkDePagoParaOrdenPendiente(
                    pendingOrder.id(), ctx.data().user().stripeCustomerId(), ctx.data().orderItems()
            );
        } catch (RuntimeException ex) {
            markPendingOrderFailedAndRestoreCart(ctx);
            throw ex;
        }

        runInTransaction(() -> orderRepository.save(new Order(
                pendingOrder.id(), ctx.data().user().id(), ctx.data().cart().id(), ctx.data().user().stripeCustomerId(),
                null, sessionResult.stripeId(), null, sessionResult.paymentUrl(),
                ctx.data().totalAmountCents(), "mxn", sessionResult.status(), ctx.data().orderItems(),
                pendingOrder.createdAt()
        )));

        return sessionResult.withOrderId(pendingOrder.id());
    }

    @Override
    @Transactional
    public StripePaymentResult createOrGetOrderCheckoutLink(String username, String orderId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(UserNotFoundException::new);

        Order order = resolveActiveUserOrder(user, orderId);
        if (order.status() == Status.PAID) {
            return new StripePaymentResult(order.stripePaymentIntentId(), null, order.paymentUrl(), Status.PAID)
                    .withOrderId(order.id());
        }

        String stripeCustomerId = order.stripeCustomerId();
        if (stripeCustomerId == null || stripeCustomerId.isBlank()) {
            stripeCustomerId = user.stripeCustomerId();
        }
        if (stripeCustomerId == null || stripeCustomerId.isBlank()) {
            stripeCustomerId = stripeCustomerPort.crearClienteEnStripe(user.username(), user.email(), user.phone());
            user = new User(user.id(), user.username(), user.email(), user.password(), user.phone(), stripeCustomerId, user.role());
            userRepository.save(user);
        }

        if (order.stripCheckoutSessionId() != null && !order.stripCheckoutSessionId().isBlank()) {
            stripePayment.cancelarCheckoutSession(order.stripCheckoutSessionId());
        }
        if (order.stripePaymentIntentId() != null && !order.stripePaymentIntentId().isBlank()) {
            stripePayment.cancelarPaymentIntent(order.stripePaymentIntentId());
        }

        StripePaymentResult sessionResult = stripePayment.crearLinkDePagoParaOrdenPendiente(
                order.id(), stripeCustomerId, order.items()
        );

        orderRepository.save(new Order(
                order.id(), order.userId(), order.cartId(), stripeCustomerId,
                null, sessionResult.stripeId(), order.stripePaymentLinkId(), sessionResult.paymentUrl(),
                order.totalAmountCents(), order.currency(), sessionResult.status(), order.items(),
                order.createdAt(), order.canceledAt()
        ));

        return sessionResult.withOrderId(order.id());
    }

    private List<Order> loadUserStripeOrders(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(UserNotFoundException::new);

        LocalDateTime now = LocalDateTime.now();
        return orderRepository.findByUserId(user.id()).stream()
                .map(order -> cancelOrderIfExpired(order, now))
                .filter(this::isStripeOrder)
                .sorted(Comparator.comparing(
                        Order::createdAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();
    }

    private boolean isStripeOrder(Order order) {
        if (order == null) {
            return false;
        }
        if (hasText(order.stripeCustomerId())
                || hasText(order.stripePaymentIntentId())
                || hasText(order.stripCheckoutSessionId())
                || hasText(order.stripePaymentLinkId())
                || hasText(order.paymentUrl())) {
            return true;
        }
        return order.items() != null && order.items().stream()
                .anyMatch(item -> item != null && hasText(item.stripePriceId()));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean matchesStatusFilter(Order order, Status status) {
        if (status == null) {
            return true;
        }
        if (status == Status.CANCELED) {
            return order.status() == Status.CANCELED || order.status() == Status.FAILED;
        }
        return order.status() == status;
    }

    @Override
    @Transactional
    public List<org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderDetailResponse> getUserOrders(String username) {
        List<Order> orders = loadUserStripeOrders(username);
        Map<String, OrderInvoice> invoiceMap = loadInvoicesMap(orders);
        return orders.stream()
                .map(order -> toOrderDetailResponse(order, invoiceMap.get(order.id())))
                .toList();
    }

    @Override
    @Transactional
    public org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderPageResponse getUserOrdersPaginated(
            String username,
            int page,
            int size,
            Status status) {
        List<Order> stripeOrders = loadUserStripeOrders(username);

        int safePage = Math.max(0, page);
        int safeSize = size > 0 ? Math.min(size, 100) : 10;

        long totalAll = stripeOrders.size();
        long totalPaid = stripeOrders.stream().filter(o -> o.status() == Status.PAID).count();
        long totalPending = stripeOrders.stream().filter(o -> o.status() == Status.PENDING).count();
        long totalCanceled = stripeOrders.stream()
                .filter(o -> o.status() == Status.CANCELED || o.status() == Status.FAILED)
                .count();

        List<Order> filtered = stripeOrders.stream()
                .filter(order -> matchesStatusFilter(order, status))
                .toList();

        long totalElements = filtered.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);
        int fromIndex = Math.min(safePage * safeSize, filtered.size());
        int toIndex = Math.min(fromIndex + safeSize, filtered.size());

        List<Order> pageSlice = filtered.subList(fromIndex, toIndex);
        Map<String, OrderInvoice> invoiceMap = loadInvoicesMap(pageSlice);

        List<org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderDetailResponse> content = pageSlice
                .stream()
                .map(order -> toOrderDetailResponse(order, invoiceMap.get(order.id())))
                .toList();

        boolean hasNext = safePage + 1 < totalPages;
        boolean hasPrevious = safePage > 0 && totalPages > 0;

        return new org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderPageResponse(
                content,
                safePage,
                safeSize,
                totalElements,
                totalPages,
                hasNext,
                hasPrevious,
                totalAll,
                totalPaid,
                totalPending,
                totalCanceled
        );
    }

    private Map<String, OrderInvoice> loadInvoicesMap(List<Order> orders) {
        if (orderInvoiceRepository == null || orders == null || orders.isEmpty()) {
            return Map.of();
        }
        List<String> orderIds = orders.stream().map(Order::id).toList();
        List<OrderInvoice> invoices = orderInvoiceRepository.findByOrderIdIn(orderIds);
        if (invoices == null || invoices.isEmpty()) {
            return Map.of();
        }
        Map<String, OrderInvoice> map = new LinkedHashMap<>();
        for (OrderInvoice invoice : invoices) {
            if (invoice != null && invoice.orderId() != null) {
                map.put(invoice.orderId(), invoice);
            }
        }
        return map;
    }

    private org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderDetailResponse toOrderDetailResponse(Order order) {
        return toOrderDetailResponse(order, null);
    }

    private org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderDetailResponse toOrderDetailResponse(
            Order order,
            OrderInvoice invoice) {
        List<org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderItemResponse> itemResponses =
                resolveNormalizedOrderItems(order).stream()
                        .map(resolved -> new org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderItemResponse(
                                resolved.productId(),
                                resolved.name(),
                                resolved.unitPriceCents(),
                                resolved.quantity(),
                                resolved.subtotalCents(),
                                resolved.imageUrl()
                        ))
                        .toList();

        LocalDateTime expiresAt = order.createdAt() != null
                ? order.createdAt().plusHours(expirationHours)
                : null;

        return new org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderDetailResponse(
                order.id(),
                order.userId(),
                order.cartId(),
                order.stripePaymentIntentId(),
                order.paymentUrl(),
                order.totalAmountCents(),
                order.currency(),
                order.status(),
                order.createdAt(),
                expiresAt,
                order.canceledAt(),
                itemResponses,
                InvoiceServiceImpl.toSummaryResponse(invoice)
        );
    }

    @Override
    @Transactional
    public StripePaymentResult getOrRetryOrderPayment(String username, String orderId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(UserNotFoundException::new);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró la orden solicitada: " + orderId));

        if (!order.userId().equals(user.id())) {
            throw new IllegalArgumentException("No tienes permisos para acceder a esta orden.");
        }

        order = cancelOrderIfExpired(order, LocalDateTime.now());
        if (order.status() == Status.CANCELED) {
            throw new ArticleBusinessConflictException(
                    "Esta orden fue cancelada por exceder el límite de " + expirationHours + " horas sin pago."
            );
        }

        if (order.status() == Status.PAID) {
            return new StripePaymentResult(order.stripePaymentIntentId(), null, order.paymentUrl(), Status.PAID)
                    .withOrderId(order.id());
        }

        // Si ya existe una Checkout Session activa en modo Elements, reutilizar su clientSecret
        if (order.stripCheckoutSessionId() != null && !order.stripCheckoutSessionId().isBlank()) {
            try {
                StripePaymentResult existingSession = stripePayment.recuperarCheckoutSession(order.stripCheckoutSessionId());
                if (existingSession != null
                        && existingSession.status() == Status.PENDING
                        && existingSession.clientSecret() != null
                        && !existingSession.clientSecret().isBlank()) {
                    return existingSession.withOrderId(order.id());
                }
            } catch (Exception e) {
                log.warn("No se pudo recuperar Checkout Session {} de la orden {}: {}",
                        order.stripCheckoutSessionId(), order.id(), e.getMessage());
            }
            stripePayment.cancelarCheckoutSession(order.stripCheckoutSessionId());
        }

        if (order.stripePaymentIntentId() != null && !order.stripePaymentIntentId().isBlank()) {
            stripePayment.cancelarPaymentIntent(order.stripePaymentIntentId());
        }

        // Generar una nueva Checkout Session (ui_mode: elements) para poder pagar en sitio
        StripePaymentResult csResult = stripePayment.crearCheckoutSessionElementsParaOrden(
                order.id(), user.stripeCustomerId(), order.items()
        );

        orderRepository.save(new Order(
                order.id(), order.userId(), order.cartId(), order.stripeCustomerId(),
                null, csResult.stripeId(), order.stripePaymentLinkId(),
                null, order.totalAmountCents(), order.currency(),
                csResult.status(), order.items(), order.createdAt(), order.canceledAt()
        ));

        return csResult.withOrderId(order.id());
    }

    @Override
    @Transactional
    public StripePaymentResult createOrGetOrderSpeiPayment(String username, String orderId) {
        return createOrGetOrderSpeiPayment(username, orderId, null);
    }

    @Override
    @Transactional
    public StripePaymentResult createOrGetOrderSpeiPayment(String username, String orderId, String recipientEmail) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(UserNotFoundException::new);

        Order order = resolveActiveUserOrder(user, orderId);
        if (order.status() == Status.PAID) {
            return new StripePaymentResult(order.stripePaymentIntentId(), null, order.paymentUrl(), Status.PAID);
        }

        String resolvedEmail = resolveRecipientEmail(recipientEmail, user);
        boolean explicitEmailProvided = recipientEmail != null && !recipientEmail.isBlank();
        return getOrCreateSpeiPaymentInternal(user, order, resolvedEmail, explicitEmailProvided);
    }

    @Override
    @Transactional
    public byte[] generateOrderSpeiPdf(String username, String orderId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(UserNotFoundException::new);

        Order order = resolveActiveUserOrder(user, orderId);
        if (order.status() == Status.PAID) {
            throw new ArticleBusinessConflictException("Esta orden ya fue pagada y no requiere ficha de pago SPEI.");
        }

        StripePaymentResult speiResult = getOrCreateSpeiPaymentInternal(user, order, user.email(), false);
        SpeiPaymentDetails speiDetails = speiResult.speiDetails();
        if (speiDetails == null) {
            throw new ArticleBusinessConflictException(
                    "Aún no hay instrucciones SPEI disponibles para esta orden. Primero confirma el pago SPEI en el formulario de checkout."
            );
        }

        LocalDateTime expiresAt = calculateOrderExpiration(order);
        return speiVoucherPdfGenerator.generateVoucherPdf(
                order,
                speiDetails,
                user.username(),
                speiDetails.recipientEmail(),
                expiresAt,
                buildVoucherItems(order)
        );
    }

    private Order resolveActiveUserOrder(User user, String orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró la orden solicitada: " + orderId));

        if (!order.userId().equals(user.id())) {
            throw new IllegalArgumentException("No tienes permisos para acceder a esta orden.");
        }

        order = cancelOrderIfExpired(order, LocalDateTime.now());
        if (order.status() == Status.CANCELED) {
            throw new ArticleBusinessConflictException(
                    "Esta orden fue cancelada por exceder el límite de " + expirationHours + " horas sin pago."
            );
        }
        return order;
    }

    private StripePaymentResult getOrCreateSpeiPaymentInternal(
            User user,
            Order order,
            String targetEmail,
            boolean forceSendEmail
    ) {
        String stripeCustomerId = order.stripeCustomerId();
        if (stripeCustomerId == null || stripeCustomerId.isBlank()) {
            stripeCustomerId = user.stripeCustomerId();
        }
        if (stripeCustomerId == null || stripeCustomerId.isBlank()) {
            stripeCustomerId = stripeCustomerPort.crearClienteEnStripe(user.username(), user.email(), user.phone());
            user = new User(user.id(), user.username(), user.email(), user.password(), user.phone(), stripeCustomerId, user.role());
            userRepository.save(user);
        }

        if (order.stripePaymentIntentId() != null && !order.stripePaymentIntentId().isBlank()) {
            try {
                StripePaymentResult existing = stripePayment.recuperarPaymentIntent(order.stripePaymentIntentId());
                if (existing != null && existing.speiDetails() != null) {
                    return finalizeSpeiResultAndNotify(existing, order, user, targetEmail, forceSendEmail);
                }
            } catch (Exception e) {
                log.warn("No se pudo recuperar el PaymentIntent {} para SPEI en orden {}: {}",
                        order.stripePaymentIntentId(), order.id(), e.getMessage());
            }
            stripePayment.cancelarPaymentIntent(order.stripePaymentIntentId());
        }

        if (order.stripCheckoutSessionId() != null && !order.stripCheckoutSessionId().isBlank()) {
            try {
                StripePaymentResult existingSession = stripePayment.recuperarCheckoutSession(order.stripCheckoutSessionId());
                if (existingSession != null && existingSession.status() == Status.PAID) {
                    return existingSession.withOrderId(order.id());
                }
                if (existingSession != null && existingSession.speiDetails() != null) {
                    Order syncedOrder = order;
                    if (existingSession.paymentUrl() != null && !existingSession.paymentUrl().equals(order.paymentUrl())) {
                        Order savedOrder = orderRepository.save(new Order(
                                order.id(), order.userId(), order.cartId(), order.stripeCustomerId(),
                                order.stripePaymentIntentId(), order.stripCheckoutSessionId(), order.stripePaymentLinkId(),
                                existingSession.paymentUrl(), order.totalAmountCents(), order.currency(),
                                order.status(), order.items(), order.createdAt(), order.canceledAt()
                        ));
                        if (savedOrder != null) {
                            syncedOrder = savedOrder;
                        }
                    }
                    return finalizeSpeiResultAndNotify(existingSession, syncedOrder, user, targetEmail, forceSendEmail);
                }
                if (existingSession != null
                        && existingSession.status() == Status.PENDING
                        && existingSession.clientSecret() != null
                        && !existingSession.clientSecret().isBlank()) {
                    return existingSession.withOrderId(order.id());
                }
            } catch (Exception e) {
                log.warn("No se pudo reutilizar la Checkout Session SPEI {} de la orden {}: {}",
                        order.stripCheckoutSessionId(), order.id(), e.getMessage());
            }
            stripePayment.cancelarCheckoutSession(order.stripCheckoutSessionId());
        }

        StripePaymentResult speiSession = stripePayment.crearCheckoutSessionSpeiParaOrden(
                order.id(),
                stripeCustomerId,
                targetEmail,
                order.items()
        );

        Order updatedOrder = orderRepository.save(new Order(
                order.id(),
                order.userId(),
                order.cartId(),
                stripeCustomerId,
                null,
                speiSession.stripeId(),
                order.stripePaymentLinkId(),
                speiSession.paymentUrl() != null ? speiSession.paymentUrl() : order.paymentUrl(),
                order.totalAmountCents(),
                order.currency(),
                speiSession.status(),
                order.items(),
                order.createdAt(),
                order.canceledAt()
        ));

        if (speiSession.speiDetails() != null) {
            return finalizeSpeiResultAndNotify(speiSession, updatedOrder, user, targetEmail, true);
        }
        return speiSession.withOrderId(updatedOrder.id());
    }

    private StripePaymentResult finalizeSpeiResultAndNotify(
            StripePaymentResult rawResult,
            Order order,
            User user,
            String recipientEmail,
            boolean shouldSendEmail
    ) {
        if (rawResult == null || rawResult.speiDetails() == null) {
            return rawResult;
        }

        LocalDateTime expiresAt = calculateOrderExpiration(order);
        SpeiPaymentDetails enrichedDetails = rawResult.speiDetails().withOrderContext(
                order.id(),
                recipientEmail,
                expiresAt
        );

        if (shouldSendEmail && speiInstructionEmailService != null && recipientEmail != null && !recipientEmail.isBlank()) {
            byte[] pdfBytes = speiVoucherPdfGenerator.generateVoucherPdf(
                    order,
                    enrichedDetails,
                    user.username(),
                    recipientEmail,
                    expiresAt,
                    buildVoucherItems(order)
            );
            speiInstructionEmailService.sendSpeiInstructions(
                    recipientEmail,
                    user.username(),
                    order.id(),
                    enrichedDetails,
                    expiresAt,
                    pdfBytes
            );
        }

        return new StripePaymentResult(
                rawResult.stripeId(),
                rawResult.clientSecret(),
                rawResult.paymentUrl(),
                rawResult.status(),
                enrichedDetails,
                order.id()
        );
    }

    private String resolveRecipientEmail(String requestedEmail, User user) {
        if (requestedEmail != null && !requestedEmail.isBlank()) {
            return requestedEmail.trim();
        }
        return user != null ? user.email() : null;
    }

    private LocalDateTime calculateOrderExpiration(Order order) {
        if (order != null && order.createdAt() != null) {
            return order.createdAt().plusHours(expirationHours);
        }
        return LocalDateTime.now().plusHours(expirationHours);
    }

    private record ResolvedOrderItem(
            String productId,
            String name,
            long unitPriceCents,
            long quantity,
            long subtotalCents,
            String imageUrl
    ) {
    }

    private List<SpeiVoucherPdfGenerator.VoucherItem> buildVoucherItems(Order order) {
        return resolveNormalizedOrderItems(order).stream()
                .map(item -> new SpeiVoucherPdfGenerator.VoucherItem(
                        item.name(),
                        item.quantity(),
                        item.unitPriceCents(),
                        item.subtotalCents()
                ))
                .toList();
    }

    /**
     * Resuelve los artículos de una orden y corrige posibles filas duplicadas heredadas en BD
     * contrastando el desglose contra el totalAmountCents real de la orden.
     */
    private List<ResolvedOrderItem> resolveNormalizedOrderItems(Order order) {
        if (order == null || order.items() == null || order.items().isEmpty()) {
            return List.of();
        }

        List<ResolvedOrderItem> rawItems = new ArrayList<>();
        Map<String, ResolvedOrderItem> deduplicatedByProduct = new LinkedHashMap<>();
        long rawTotal = 0L;
        long deduplicatedTotal = 0L;

        for (ItemOrden item : order.items()) {
            Product product = productRepository.findById(item.productId()).orElse(null);
            String name = product != null ? product.name() : "Producto (" + item.productId() + ")";
            long unitPrice = product != null && product.unitAmountCents() != null ? product.unitAmountCents() : 0L;
            String imageUrl = product != null ? product.imageUrl() : null;
            long qty = item.cantidad() != null ? item.cantidad() : 1L;
            long subtotal = unitPrice * qty;

            ResolvedOrderItem resolved = new ResolvedOrderItem(
                    item.productId(),
                    name,
                    unitPrice,
                    qty,
                    subtotal,
                    imageUrl
            );
            rawItems.add(resolved);
            rawTotal += subtotal;

            if (!deduplicatedByProduct.containsKey(item.productId())) {
                deduplicatedByProduct.put(item.productId(), resolved);
                deduplicatedTotal += subtotal;
            }
        }

        if (rawItems.size() > deduplicatedByProduct.size() && order.totalAmountCents() != null) {
            long diffRaw = Math.abs(rawTotal - order.totalAmountCents());
            long diffDedup = Math.abs(deduplicatedTotal - order.totalAmountCents());
            if (diffDedup < diffRaw) {
                return List.copyOf(deduplicatedByProduct.values());
            }
        }

        return rawItems;
    }

    @Override
    public org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderDetailResponse syncOrderPaymentStatus(
            String username,
            String orderId,
            String sessionId
    ) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(UserNotFoundException::new);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró la orden solicitada: " + orderId));

        if (!order.userId().equals(user.id())) {
            throw new IllegalArgumentException("No tienes permisos para consultar esta orden.");
        }

        if (order.status() == Status.PENDING) {
            String primarySessionId = hasText(sessionId) ? sessionId.trim() : order.stripCheckoutSessionId();
            var paidPi = stripePayment.resolvePaidPaymentIntentId(
                    primarySessionId,
                    order.stripePaymentIntentId(),
                    order.id()
            );
            if (paidPi.isEmpty() && hasText(sessionId) && hasText(order.stripCheckoutSessionId())
                    && !sessionId.trim().equals(order.stripCheckoutSessionId())) {
                paidPi = stripePayment.resolvePaidPaymentIntentId(
                        order.stripCheckoutSessionId(),
                        order.stripePaymentIntentId(),
                        order.id()
                );
            }

            if (paidPi.isPresent()) {
                ProcessPaymentWebhookUseCase webhookUseCase = processPaymentWebhookProvider != null
                        ? processPaymentWebhookProvider.getIfAvailable()
                        : null;
                if (webhookUseCase != null) {
                    log.info("🔄 [Sync Pago Stripe] Orden {} confirmada como pagada en Stripe (PI: {}). Ejecutando markOrderAsPaid...",
                            order.id(), paidPi.get());
                    webhookUseCase.markOrderAsPaid(order.id(), Status.PAID, paidPi.get(), order.stripeCustomerId());
                    order = orderRepository.findById(order.id()).orElse(order);
                }
            }
        } else if (order.status() == Status.PAID) {
            InvoiceUseCase invoiceUseCase = invoiceUseCaseProvider != null
                    ? invoiceUseCaseProvider.getIfAvailable()
                    : null;
            if (invoiceUseCase != null && invoiceUseCase.hasPendingInvoiceRequest(order.id())) {
                invoiceUseCase.processAutomaticInvoiceOnPaymentConfirmed(order.id());
            }
        }

        OrderInvoice invoice = orderInvoiceRepository != null
                ? orderInvoiceRepository.findByOrderId(order.id()).orElse(null)
                : null;
        return toOrderDetailResponse(order, invoice);
    }

    @Override
    @Transactional
    public org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderDetailResponse cancelOrder(
            String username,
            String orderId
    ) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(UserNotFoundException::new);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No se encontró la orden solicitada: " + orderId));

        if (!order.userId().equals(user.id())) {
            throw new IllegalArgumentException("No tienes permisos para cancelar esta orden.");
        }

        if (order.status() == Status.PAID) {
            throw new ArticleBusinessConflictException("No es posible cancelar una orden que ya fue pagada.");
        }

        if (order.status() == Status.CANCELED) {
            return toOrderDetailResponse(order);
        }

        LocalDateTime now = LocalDateTime.now();
        Order canceledOrder = cancelOrderAndCart(order, now, "Cancelación Manual por Usuario");
        return toOrderDetailResponse(canceledOrder);
    }

    @Override
    @Transactional
    public int cancelExpiredPendingOrders() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minusHours(expirationHours);
        List<Order> expiredOrders = orderRepository.findPendingOrdersCreatedBefore(cutoff);
        int canceledCount = 0;

        for (Order order : expiredOrders) {
            Order updated = cancelOrderIfExpired(order, now);
            if (updated.status() == Status.CANCELED) {
                canceledCount++;
            }
        }

        if (canceledCount > 0) {
            log.info("⏰ [Expiración Órdenes] Se cancelaron automáticamente {} órdenes/carritos pendientes tras {} horas sin pago.",
                    canceledCount, expirationHours);
        }
        return canceledCount;
    }

    private Order cancelOrderIfExpired(Order order, LocalDateTime now) {
        if (order == null || order.status() != Status.PENDING || order.createdAt() == null) {
            return order;
        }
        LocalDateTime expirationDate = order.createdAt().plusHours(expirationHours);
        if (expirationDate.isAfter(now)) {
            return order;
        }
        return cancelOrderAndCart(
                order,
                now,
                "Expiración automática (" + expirationHours + " horas sin pago)"
        );
    }

    private Order cancelOrderAndCart(Order order, LocalDateTime canceledAt, String reason) {
        if (order.stripePaymentIntentId() != null && !order.stripePaymentIntentId().isBlank()) {
            stripePayment.cancelarPaymentIntent(order.stripePaymentIntentId());
        }
        if (order.stripCheckoutSessionId() != null && !order.stripCheckoutSessionId().isBlank()) {
            stripePayment.cancelarCheckoutSession(order.stripCheckoutSessionId());
        }

        Order canceledOrder = new Order(
                order.id(),
                order.userId(),
                order.cartId(),
                order.stripeCustomerId(),
                order.stripePaymentIntentId(),
                order.stripCheckoutSessionId(),
                order.stripePaymentLinkId(),
                order.paymentUrl(),
                order.totalAmountCents(),
                order.currency(),
                Status.CANCELED,
                order.items(),
                order.createdAt(),
                canceledAt
        );
        Order savedOrder = orderRepository.save(canceledOrder);

        if (order.cartId() != null && !order.cartId().isBlank()) {
            cartRepository.findById(order.cartId()).ifPresent(cart -> {
                if (cart.status() != CartStatus.CANCELED) {
                    cartRepository.save(new Cart(
                            cart.id(),
                            cart.userId(),
                            CartStatus.CANCELED,
                            canceledAt,
                            cart.items()
                    ));
                }
            });
        }

        log.info("🚫 [{}] Orden {} y carrito {} cancelados (creada: {}, cancelada: {}).",
                reason, order.id(), order.cartId(), order.createdAt(), canceledAt);

        return savedOrder;
    }
}
