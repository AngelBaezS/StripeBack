package org.angel.paymentclean.infrastructure.adapters.out.persistence.repository;

import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.model.ItemOrden;
import org.angel.paymentclean.domain.model.Order;
import org.angel.paymentclean.domain.port.out.OrderRepositoryPort;
import org.angel.paymentclean.infrastructure.adapters.out.persistence.entity.OrderEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
public class OrderRepositoryAdapter implements OrderRepositoryPort {

    private final DataOrderRepository dataOrderRepository;

    public OrderRepositoryAdapter(DataOrderRepository springDataOrderRepository) {
        this.dataOrderRepository = springDataOrderRepository;
    }

    @Override
    public Order save(Order order) {
        if (order.id() != null) {
            Optional<OrderEntity> existingOpt = dataOrderRepository.findById(order.id());
            if (existingOpt.isPresent()) {
                OrderEntity entity = existingOpt.get();
                entity.setStatus(order.status());
                entity.setCartId(order.cartId());
                if (order.stripeCustomerId() != null) {
                    entity.setStripeCustomerId(order.stripeCustomerId());
                }
                entity.setStripePaymentIntentId(order.stripePaymentIntentId());
                entity.setStripeCheckoutSessionId(order.stripCheckoutSessionId());
                entity.setStripePaymentLinkId(order.stripePaymentLinkId());
                entity.setPaymentUrl(order.paymentUrl());
                entity.setTotalAmountCents(order.totalAmountCents());
                entity.setCurrency(order.currency());
                if (entity.getCreatedAt() == null && order.createdAt() != null) {
                    entity.setCreatedAt(order.createdAt());
                }
                entity.setCanceledAt(order.canceledAt());

                // Los ítems de una orden se fijan al crearla; evitar limpiar/reinsertar en actualizaciones posteriores
                if (entity.getItems().isEmpty() && order.items() != null) {
                    for (ItemOrden item : order.items()) {
                        entity.addItem(item.productId(), item.stripePriceId(), item.cantidad());
                    }
                }
                OrderEntity savedEntity = dataOrderRepository.save(entity);
                return toDomain(savedEntity);
            }
        }

        OrderEntity newEntity = toEntity(order);
        OrderEntity savedEntity = dataOrderRepository.save(newEntity);
        return toDomain(savedEntity);
    }

    @Override
    public Optional<Order> findById(String id) {
        return dataOrderRepository.findById(id).map(this::toDomain);
    }

    @Override
    public Optional<Order> findByStripePaymentIntentId(String stripePaymentIntentId) {
        return dataOrderRepository.findByStripePaymentIntentId(stripePaymentIntentId).map(this::toDomain);
    }

    @Override
    public List<Order> findByUserId(String userId) {
        return dataOrderRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Order> findPendingOrdersCreatedBefore(LocalDateTime cutoff) {
        return dataOrderRepository.findByStatusAndCreatedAtBefore(Status.PENDING, cutoff).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void updateOrderStatusAndPaymentIntent(String orderId, Status status, String stripePaymentIntentId) {
        dataOrderRepository.findById(orderId).ifPresent(entity -> {
            entity.setStatus(status);
            if (stripePaymentIntentId != null) {
                entity.setStripePaymentIntentId(stripePaymentIntentId);
            }
            if (status == Status.CANCELED && entity.getCanceledAt() == null) {
                entity.setCanceledAt(LocalDateTime.now());
            }
            dataOrderRepository.save(entity);
        });
    }

    // Mapeo de Dominio a Entidad JPA
    private OrderEntity toEntity(Order domain) {
        OrderEntity entity = new OrderEntity(
                domain.id(),
                domain.userId(),
                domain.cartId(),
                domain.stripeCustomerId(),
                domain.stripePaymentIntentId(),
                domain.stripCheckoutSessionId(),
                domain.stripePaymentLinkId(),
                domain.paymentUrl(),
                domain.totalAmountCents(),
                domain.currency(),
                domain.status(),
                domain.createdAt() != null ? domain.createdAt() : LocalDateTime.now(),
                domain.canceledAt()
        );
        if (domain.items() != null) {
            for (ItemOrden item : domain.items()) {
                entity.addItem(item.productId(), item.stripePriceId(), item.cantidad());
            }
        }
        return entity;
    }

    // Mapeo de Entidad JPA a Modelo de Dominio
    private Order toDomain(OrderEntity entity) {
        List<ItemOrden> items = entity.getItems() != null
                ? entity.getItems().stream()
                    .map(item -> new ItemOrden(item.getProductId(), item.getStripePriceId(), item.getQuantity()))
                    .toList()
                : Collections.emptyList();

        return new Order(
                entity.getId(),
                entity.getUserId(),
                entity.getCartId(),
                entity.getStripeCustomerId(),
                entity.getStripePaymentIntentId(),
                entity.getStripeCheckoutSessionId(),
                entity.getStripePaymentLinkId(),
                entity.getPaymentUrl(),
                entity.getTotalAmountCents(),
                entity.getCurrency(),
                entity.getStatus(),
                items,
                entity.getCreatedAt(),
                entity.getCanceledAt()
        );
    }
}
