package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record CreateProductRequest(
        String name,
        String description,
        Integer stock,
        long unitAmountCents,
        @JsonAlias({"image_url", "imageUrl"}) String imageUrl
) {
    public CreateProductRequest(String name, String description, Integer stock, long unitAmountCents) {
        this(name, description, stock, unitAmountCents, null);
    }
}
