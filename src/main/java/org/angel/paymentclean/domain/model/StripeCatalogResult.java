package org.angel.paymentclean.domain.model;

public record StripeCatalogResult(
        String stripeProductId,
        String stripePriceId
) {
}
