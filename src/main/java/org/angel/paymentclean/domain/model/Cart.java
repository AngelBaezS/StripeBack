package org.angel.paymentclean.domain.model;

import org.angel.paymentclean.domain.enums.CartStatus;

import java.time.LocalDateTime;
import java.util.List;

public record Cart(
        String id,
        String userId,
        CartStatus status,
        LocalDateTime updatedAt,
        List<CartItem> items
) {
}
