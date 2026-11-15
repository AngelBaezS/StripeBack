package org.angel.paymentclean.application.service;

import org.angel.paymentclean.domain.enums.CartStatus;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.model.Cart;
import org.angel.paymentclean.domain.model.ItemOrden;
import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.model.Product;
import org.angel.paymentclean.domain.port.in.ProcessPaymentWebhookUseCase;
import org.angel.paymentclean.domain.port.out.CartRepositoryPort;
import org.angel.paymentclean.domain.port.out.OrderRepositoryPort;
import org.angel.paymentclean.domain.port.out.ProductRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class ProcessPaymentWebhookService implements ProcessPaymentWebhookUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessPaymentWebhookService.class);

    private final OrderRepositoryPort orderRepository;
    private final ProductRepositoryPort productRepository;
    private final CartRepositoryPort cartRepository;

    public ProcessPaymentWebhookService(OrderRepositoryPort orderRepository, ProductRepositoryPort productRepository) {
        this(orderRepository, productRepository, null);
    }

    @Autowired
    public ProcessPaymentWebhookService(
            OrderRepositoryPort orderRepository,
            ProductRepositoryPort productRepository,
            CartRepositoryPort cartRepository
    ) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.cartRepository = cartRepository;
    }

    @Override
    @Transactional
    public void markOrderAsPaid(String orderId, Status status, String stripePaymentIntentId, String stripeCustomerId) {
        Order order = resolveOrder(orderId, stripePaymentIntentId);
        if (order == null) {
            log.warn("⚠️ [Idempotencia] Orden #{} (PI: {}) no encontrada para procesar pago.", orderId, stripePaymentIntentId);
            return;
        }

        // Protección de Idempotencia de Dominio: si ya está pagada, omitir
        if (order.status() == Status.PAID) {
            log.info("🛡️ [Idempotencia] La orden #{} ya se encuentra en estado PAID. Omitiendo procesamiento duplicado.", order.id());
            return;
        }

        // Actualizar el stock de cada producto comprado
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
                        log.info("📉 [Inventario] Stock actualizado para '{}' (ID: {}): {} -> {}",
                                product.name(), product.id(), currentStock, updatedStock);
                    });
                }
            }
        }

        orderRepository.updateOrderStatusAndPaymentIntent(order.id(), status != null ? status : Status.PAID, stripePaymentIntentId);
        log.info("✅ [Pago Confirmado] Orden #{} actualizada a PAID con éxito.", order.id());
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
