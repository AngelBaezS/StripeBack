package org.angel.paymentclean.domain.model;

public record CartItem(
        String id,
        String productId,
        Integer quantity
) {
}
