package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

public record ProductForUser(
        String id,
        String name,
        String description,
        Long unitAmountCents,
        String imageUrl
) {
    public ProductForUser(String id, String name, String description, Long unitAmountCents) {
        this(id, name, description, unitAmountCents, null);
    }
}
