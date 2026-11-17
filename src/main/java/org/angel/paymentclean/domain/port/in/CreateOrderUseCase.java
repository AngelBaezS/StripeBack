package org.angel.paymentclean.domain.port.in;

import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.model.StripePaymentResult;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderDetailResponse;
import org.angel.paymentclean.infrastructure.adapters.in.rest.dto.OrderPageResponse;

import java.util.List;

public interface CreateOrderUseCase {
    StripePaymentResult createOrderWithDirectPayment(String username);
    StripePaymentResult createOrderWithSpeiPayment(String username);
    StripePaymentResult createOrderWithSpeiPayment(String username, String recipientEmail);
    StripePaymentResult createOrderWithCheckoutLink(String username);
    StripePaymentResult createOrGetOrderCheckoutLink(String username, String orderId);
    List<OrderDetailResponse> getUserOrders(String username);
    OrderPageResponse getUserOrdersPaginated(String username, int page, int size, Status status);
    StripePaymentResult getOrRetryOrderPayment(String username, String orderId);
    StripePaymentResult createOrGetOrderSpeiPayment(String username, String orderId);
    StripePaymentResult createOrGetOrderSpeiPayment(String username, String orderId, String recipientEmail);
    byte[] generateOrderSpeiPdf(String username, String orderId);
    OrderDetailResponse cancelOrder(String username, String orderId);
    OrderDetailResponse syncOrderPaymentStatus(String username, String orderId, String sessionId);
    int cancelExpiredPendingOrders();
}
