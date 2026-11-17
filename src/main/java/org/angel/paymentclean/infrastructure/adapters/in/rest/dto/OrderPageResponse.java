package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import java.util.List;

public record OrderPageResponse(
        List<OrderDetailResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        boolean hasPrevious,
        long totalAll,
        long totalPaid,
        long totalPending,
        long totalCanceled
) {}
