package org.angel.paymentclean.domain.model;

import org.angel.paymentclean.domain.enums.Status;

public record StripePaymentResult(
        String stripeId,
        String clientSecret,
        String paymentUrl,
        Status status,
        SpeiPaymentDetails speiDetails,
        String orderId
) {
    public StripePaymentResult(String stripeId, String clientSecret, String paymentUrl, Status status) {
        this(stripeId, clientSecret, paymentUrl, status, null, null);
    }

    public StripePaymentResult(
            String stripeId,
            String clientSecret,
            String paymentUrl,
            Status status,
            SpeiPaymentDetails speiDetails
    ) {
        this(stripeId, clientSecret, paymentUrl, status, speiDetails, null);
    }

    public StripePaymentResult withOrderId(String orderId) {
        return new StripePaymentResult(stripeId, clientSecret, paymentUrl, status, speiDetails, orderId);
    }
}
