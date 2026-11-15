package org.angel.paymentclean.domain.port.in;

import org.angel.paymentclean.domain.enums.Status;

public interface ProcessPaymentWebhookUseCase {
    void markOrderAsPaid(String orderId, Status status, String stripePaymentIntentId, String stripeCustomerId);
    void markOrderAsPaymentFailed(String orderId, Status status, String errorMessage);
    default void markOrderAsPaymentFailed(String orderId, String stripePaymentIntentId, Status status, String errorMessage) {
        markOrderAsPaymentFailed(orderId, status, errorMessage);
    }
    void markOrderAsCanceled(String orderId, String stripePaymentIntentId, String reason);
    void linkPaymentIntentToOrder(String orderId, String stripePaymentIntentId);
}
