package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

public record AddCartItemRequest(
        @JsonAlias({"product_id", "productId"}) String productId,
        Integer quantity
) {
}
