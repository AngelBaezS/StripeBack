package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.enums.CartStatus;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.model.Cart;
import org.angel.paymentclean.domain.model.ItemOrden;
import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.model.User;
import org.angel.paymentclean.domain.port.in.InvoiceUseCase;
import org.angel.paymentclean.domain.port.in.ProcessPaymentWebhookUseCase;
import org.angel.paymentclean.domain.port.out.CartRepositoryPort;
import org.angel.paymentclean.domain.port.out.OrderRepositoryPort;
import org.angel.paymentclean.domain.port.out.ProductRepositoryPort;
import org.angel.paymentclean.domain.port.out.UserRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Service
public class ProcessPaymentWebhookService implements ProcessPaymentWebhookUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessPaymentWebhookService.class);

    private final OrderRepositoryPort orderRepository;
    private final ProductRepositoryPort productRepository;
    private final CartRepositoryPort cartRepository;
    private final UserRepositoryPort userRepository;
    private final MailtrapEmailService mailtrapEmailService;
    private final InvoiceUseCase invoiceUseCase;
    private final TransactionTemplate requiresNewTxTemplate;

    public ProcessPaymentWebhookService(OrderRepositoryPort orderRepository, ProductRepositoryPort productRepository) {
        this(orderRepository, productRepository, null, null, null, null, null);
    }

    public ProcessPaymentWebhookService(
            OrderRepositoryPort orderRepository,
            ProductRepositoryPort productRepository,
            CartRepositoryPort cartRepository
    ) {
        this(orderRepository, productRepository, cartRepository, null, null, null, null);
    }

    public ProcessPaymentWebhookService(
            OrderRepositoryPort orderRepository,
            ProductRepositoryPort productRepository,
            CartRepositoryPort cartRepository,
            UserRepositoryPort userRepository,
            MailtrapEmailService mailtrapEmailService,
            InvoiceUseCase invoiceUseCase
    ) {
        this(orderRepository, productRepository, cartRepository, userRepository, mailtrapEmailService, invoiceUseCase, null);
    }

    @Autowired
    public ProcessPaymentWebhookService(
            OrderRepositoryPort orderRepository,
            ProductRepositoryPort productRepository,
            CartRepositoryPort cartRepository,
            UserRepositoryPort userRepository,
            MailtrapEmailService mailtrapEmailService,
            InvoiceUseCase invoiceUseCase,
            ObjectProvider<PlatformTransactionManager> transactionManagerProvider
    ) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.cartRepository = cartRepository;
        this.userRepository = userRepository;
        this.mailtrapEmailService = mailtrapEmailService;
        this.invoiceUseCase = invoiceUseCase;
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

    private <T> T runInTransaction(Supplier<T> action) {
        if (requiresNewTxTemplate != null) {
            return requiresNewTxTemplate.execute(status -> action.get());
        }
        return action.get();
    }

    private record PaidOrderUpdateResult(Order order, Status resolvedStatus, List<MailtrapEmailService.OrderItemSummary> emailItems) {}

    @Override
    public void markOrderAsPaid(String orderId, Status status, String stripePaymentIntentId, String stripeCustomerId) {
        PaidOrderUpdateResult update = runInTransaction(() -> {
            Order order = resolveOrder(orderId, stripePaymentIntentId);
            if (order == null) {
                log.warn("⚠️ [Idempotencia] Orden #{} (PI: {}) no encontrada para procesar pago.", orderId, stripePaymentIntentId);
                return null;
            }

            // Protección de Idempotencia de Dominio: si ya está pagada, omitir
            if (order.status() == Status.PAID) {
                log.info("🛡️ [Idempotencia] La orden #{} ya se encuentra en estado PAID. Omitiendo procesamiento duplicado.", order.id());
                return null;
            }

            // Actualizar el stock de cada producto comprado y recopilar resumen para correo
            List<MailtrapEmailService.OrderItemSummary> emailItems = new ArrayList<>();
            if (order.items() != null) {
                for (ItemOrden item : order.items()) {
                    if (item.productId() != null) {
                        productRepository.findById(item.productId()).ifPresent(product -> {
                            int currentStock = product.stock() != null ? product.stock() : 0;
                            int qtyPurchased = item.cantidad() != null ? item.cantidad().intValue() : 0;
                            int updatedStock = Math.max(0, currentStock - qtyPurchased);

                            Product updatedProduct = new Product(
                                    product.id(),
                                    product.name(),
                                    product.description(),
                                    updatedStock,
                                    product.active(),
                                    product.stripeProductId(),
                                    product.stripePriceId(),
                                    product.unitAmountCents(),
                                    product.currency(),
                                    product.imageUrl()
                            );
                            productRepository.save(updatedProduct);
                            long unitCents = product.unitAmountCents() != null ? product.unitAmountCents() : 0L;
                            emailItems.add(new MailtrapEmailService.OrderItemSummary(
                                    product.name(),
                                    qtyPurchased > 0 ? qtyPurchased : 1L,
                                    unitCents * (qtyPurchased > 0 ? qtyPurchased : 1L)
                            ));
                            log.info("📉 [Inventario] Stock actualizado para '{}' (ID: {}): {} -> {}",
                                    product.name(), product.id(), currentStock, updatedStock);
                        });
                    }
                }
            }

            Status resolvedStatus = status != null ? status : Status.PAID;
            orderRepository.updateOrderStatusAndPaymentIntent(order.id(), resolvedStatus, stripePaymentIntentId);
            log.info("✅ [Pago Confirmado] Orden #{} actualizada a PAID con éxito.", order.id());
            return new PaidOrderUpdateResult(order, resolvedStatus, emailItems);
        });

        if (update != null) {
            notifyAndProcessInvoice(update.order(), stripePaymentIntentId, update.resolvedStatus(), update.emailItems());
        }
    }

    private void notifyAndProcessInvoice(
            Order order,
            String stripePaymentIntentId,
            Status resolvedStatus,
            List<MailtrapEmailService.OrderItemSummary> emailItems
    ) {
        try {
            boolean hasPendingInvoice = invoiceUseCase != null && invoiceUseCase.hasPendingInvoiceRequest(order.id());

            if (mailtrapEmailService != null && userRepository != null && order.userId() != null) {
                User user = userRepository.findById(order.userId()).orElse(null);
                if (user != null) {
                    Order paidOrderSnapshot = new Order(
                            order.id(),
                            order.userId(),
                            order.cartId(),
                            order.stripeCustomerId(),
                            stripePaymentIntentId != null ? stripePaymentIntentId : order.stripePaymentIntentId(),
                            order.stripCheckoutSessionId(),
                            order.stripePaymentLinkId(),
                            order.paymentUrl(),
                            order.totalAmountCents(),
                            order.currency(),
                            resolvedStatus,
                            order.items(),
                            order.createdAt(),
                            order.canceledAt()
                    );
                    mailtrapEmailService.sendPaymentConfirmationEmail(paidOrderSnapshot, user, emailItems, hasPendingInvoice);
                }
            }

            if (hasPendingInvoice) {
                invoiceUseCase.processAutomaticInvoiceOnPaymentConfirmed(order.id());
            }
        } catch (Exception ex) {
            log.warn("⚠️ [Post-Pago Mailtrap/CFDI] Error no crítico tras confirmar pago de orden {}: {}",
                    order.id(), ex.getMessage());
        }
    }

    @Override
    @Transactional
    public void markOrderAsPaymentFailed(String orderId, Status status, String errorMessage) {
        markOrderAsPaymentFailed(orderId, null, status, errorMessage);
    }

    @Override
    @Transactional
    public void markOrderAsPaymentFailed(String orderId, String stripePaymentIntentId, Status status, String errorMessage) {
        Order order = resolveOrder(orderId, stripePaymentIntentId);
        if (order == null) {
            log.warn("⚠️ [Idempotencia] Orden #{} (PI: {}) no encontrada para registrar fallo de pago.", orderId, stripePaymentIntentId);
            return;
        }

        // Si la orden ya se pagó con éxito o fue cancelada, ignorar eventos de fallo tardíos
        if (order.status() == Status.PAID || order.status() == Status.CANCELED) {
            log.info("🛡️ [Idempotencia] La orden #{} ya está en estado {}. Ignorando evento de fallo tardío.", order.id(), order.status());
            return;
        }

        orderRepository.updateOrderStatusAndPaymentIntent(order.id(), status != null ? status : Status.FAILED, stripePaymentIntentId);
        log.info("⚠️ [Pago Fallido] Orden #{} actualizada a FAILED. Motivo: {}", order.id(), errorMessage);
    }

    @Override
    @Transactional
    public void markOrderAsCanceled(String orderId, String stripePaymentIntentId, String reason) {
        Order order = resolveOrder(orderId, stripePaymentIntentId);
        if (order == null) {
            log.warn("⚠️ [Idempotencia] Orden #{} (PI: {}) no encontrada para registrar cancelación.", orderId, stripePaymentIntentId);
            return;
        }

        if (order.status() == Status.PAID || order.status() == Status.CANCELED) {
            log.info("🛡️ [Idempotencia] La orden #{} ya se encuentra en estado {}. Omitiendo cancelación.", order.id(), order.status());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        Order canceledOrder = new Order(
                order.id(),
                order.userId(),
                order.cartId(),
                order.stripeCustomerId(),
                order.stripePaymentIntentId() != null ? order.stripePaymentIntentId() : stripePaymentIntentId,
                order.stripCheckoutSessionId(),
                order.stripePaymentLinkId(),
                order.paymentUrl(),
                order.totalAmountCents(),
                order.currency(),
                Status.CANCELED,
                order.items(),
                order.createdAt(),
                now
        );
        orderRepository.save(canceledOrder);

        if (cartRepository != null && order.cartId() != null && !order.cartId().isBlank()) {
            cartRepository.findById(order.cartId()).ifPresent(cart -> {
                if (cart.status() != CartStatus.CANCELED) {
                    cartRepository.save(new Cart(
                            cart.id(),
                            cart.userId(),
                            CartStatus.CANCELED,
                            now,
                            cart.items()
                    ));
                }
            });
        }

        log.info("🚫 [Orden Cancelada] Orden #{} marcada como CANCELED ({}). Motivo: {}", order.id(), now, reason);
    }

    @Override
    @Transactional
    public void linkPaymentIntentToOrder(String orderId, String stripePaymentIntentId) {
        if (orderId == null || orderId.isBlank() || stripePaymentIntentId == null || stripePaymentIntentId.isBlank()) {
            return;
        }
        orderRepository.findById(orderId).ifPresent(order -> {
            if (order.status() == Status.PENDING && (order.stripePaymentIntentId() == null || order.stripePaymentIntentId().isBlank())) {
                orderRepository.updateOrderStatusAndPaymentIntent(order.id(), Status.PENDING, stripePaymentIntentId);
                log.info("🔗 PaymentIntent {} vinculado a la orden {} (en espera de acreditación SPEI).", stripePaymentIntentId, order.id());
            }
        });
    }

    private Order resolveOrder(String orderId, String stripePaymentIntentId) {
        if (orderId != null && !orderId.isBlank()) {
            var byId = orderRepository.findById(orderId);
            if (byId.isPresent()) {
                return byId.get();
            }
        }
        if (stripePaymentIntentId != null && !stripePaymentIntentId.isBlank()) {
            return orderRepository.findByStripePaymentIntentId(stripePaymentIntentId).orElse(null);
        }
        return null;
    }
}
