package org.angel.paymentclean.domain.model;

import org.angel.paymentclean.domain.enums.Status;

import java.time.LocalDateTime;
import java.util.List;

public record Order(
        String id,
        String userId,
        String cartId,
        String stripeCustomerId,
        String stripePaymentIntentId,
        String stripCheckoutSessionId,
        String stripePaymentLinkId,
        String paymentUrl,
        Long totalAmountCents,
        String currency,
        Status status,
        List<ItemOrden> items,
        LocalDateTime createdAt,
        LocalDateTime canceledAt
) {
    public Order(
            String id,
            String userId,
            String cartId,
            String stripeCustomerId,
            String stripePaymentIntentId,
            String stripCheckoutSessionId,
            String stripePaymentLinkId,
            String paymentUrl,
            Long totalAmountCents,
            String currency,
            Status status,
            List<ItemOrden> items,
            LocalDateTime createdAt
    ) {
        this(
                id,
                userId,
                cartId,
                stripeCustomerId,
                stripePaymentIntentId,
                stripCheckoutSessionId,
                stripePaymentLinkId,
                paymentUrl,
                totalAmountCents,
                currency,
                status,
                items,
                createdAt,
                null
        );
    }

    public Order(
            String id,
            String userId,
            String cartId,
            String stripeCustomerId,
            String stripePaymentIntentId,
            String stripCheckoutSessionId,
            String stripePaymentLinkId,
            String paymentUrl,
            Long totalAmountCents,
            String currency,
            Status status,
            List<ItemOrden> items
    ) {
        this(
                id,
                userId,
                cartId,
                stripeCustomerId,
                stripePaymentIntentId,
                stripCheckoutSessionId,
                stripePaymentLinkId,
                paymentUrl,
                totalAmountCents,
                currency,
                status,
                items,
                null,
                null
        );
    }
}
