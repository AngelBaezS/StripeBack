package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import org.angel.paymentclean.domain.enums.Status;

import java.util.List;

public record OrderItemResponse(
        String productId,
        String productName,
        Long unitAmountCents,
        Long quantity,
        Long subtotalCents,
        String imageUrl
) {}

