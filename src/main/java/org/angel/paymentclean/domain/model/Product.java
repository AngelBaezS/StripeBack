package org.angel.paymentclean.domain.model;

import org.angel.paymentclean.domain.exception.InvalidProductException;

public record Product(
        String id,
        String name,
        String description,
        Integer stock,
        Boolean active,
        String stripeProductId,
        String stripePriceId,
        Long unitAmountCents,
        String currency,
        String imageUrl
)
{
    public Product {
        if (name == null || name.isBlank()){
            throw new InvalidProductException("El nombre no puede estar vacio");
        }
        if (description == null || description.isBlank()) {
            throw new InvalidProductException("La descripción no puede estar vacía");
        }
        currency = "mxn";
    }

    public Product(String id, String name, String description, Integer stock, Boolean active,
                   String stripeProductId, String stripePriceId, Long unitAmountCents, String currency) {
        this(id, name, description, stock, active, stripeProductId, stripePriceId, unitAmountCents, currency, null);
    }
}
