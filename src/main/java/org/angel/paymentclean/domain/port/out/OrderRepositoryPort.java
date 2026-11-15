package org.angel.paymentclean.domain.port.out;

import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.model.Order;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepositoryPort {
    Order save(Order order);
    Optional<Order> findById(String id);
    Optional<Order> findByStripePaymentIntentId(String stripePaymentIntentId);
    List<Order> findByUserId(String userId);
    List<Order> findPendingOrdersCreatedBefore(LocalDateTime cutoff);
    void updateOrderStatusAndPaymentIntent(String orderId, Status status, String stripePaymentIntentId);
}
