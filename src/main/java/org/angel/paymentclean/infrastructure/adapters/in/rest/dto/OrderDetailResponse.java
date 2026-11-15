package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import org.angel.paymentclean.domain.enums.Status;

import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
        String id,
        String userId,
        String cartId,
        String stripePaymentIntentId,
        String paymentUrl,
        Long totalAmountCents,
        String currency,
        Status status,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime canceledAt,
        List<OrderItemResponse> items
) {}

