package org.angel.paymentclean.domain.model;

import org.angel.paymentclean.domain.enums.CartStatus;

import java.time.LocalDateTime;
import java.util.List;

public record CartDetail(
        String cartId,
        String userId,
        CartStatus status,
        LocalDateTime updatedAt,
        List<CartItemDetail> items,
        Long totalAmountCents
) {
}

