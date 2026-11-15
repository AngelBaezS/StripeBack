package org.angel.paymentclean.domain.model;

public record ItemOrden(
        String productId,
        String stripePriceId,
        Long cantidad
) {
}
