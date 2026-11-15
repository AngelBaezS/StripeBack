package org.angel.paymentclean.domain.model;

public record CartItemDetail(
        String id,
        String productId,
        String productName,
        String description,
        String imageUrl,
        Long unitAmountCents,
        Integer quantity,
        Long subtotalCents,
        Integer availableStock
) {
}

