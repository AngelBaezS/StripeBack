package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import org.angel.paymentclean.domain.enums.InvoiceStatus;

import java.time.LocalDateTime;

public record InvoiceSummaryResponse(
        String id,
        String orderId,
        InvoiceStatus status,
        String cfdiExpressId,
        String uuid,
        String rfc,
        String legalName,
        String zipCode,
        String taxRegime,
        String cfdiUse,
        String email,
        String formaPago,
        boolean hasPdf,
        boolean hasXml,
        String errorMessage,
        LocalDateTime issuedAt,
        LocalDateTime createdAt,
        Boolean emailSent
) {
    public InvoiceSummaryResponse(
            String id,
            String orderId,
            InvoiceStatus status,
            String cfdiExpressId,
            String uuid,
            String rfc,
            String legalName,
            String zipCode,
            String taxRegime,
            String cfdiUse,
            String email,
            String formaPago,
            boolean hasPdf,
            boolean hasXml,
            String errorMessage,
            LocalDateTime issuedAt,
            LocalDateTime createdAt
    ) {
        this(
                id, orderId, status, cfdiExpressId, uuid, rfc, legalName, zipCode,
                taxRegime, cfdiUse, email, formaPago, hasPdf, hasXml, errorMessage,
                issuedAt, createdAt, null
        );
    }
}
