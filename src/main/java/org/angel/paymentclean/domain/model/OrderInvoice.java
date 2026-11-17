package org.angel.paymentclean.domain.model;

import org.angel.paymentclean.domain.enums.InvoiceStatus;

import java.time.LocalDateTime;

public record OrderInvoice(
        String id,
        String orderId,
        String userId,
        String rfc,
        String legalName,
        String zipCode,
        String taxRegime,
        String cfdiUse,
        String email,
        String formaPago,
        String cfdiExpressId,
        String uuid,
        InvoiceStatus status,
        String pdfUrl,
        String xmlUrl,
        String errorMessage,
        LocalDateTime issuedAt,
        LocalDateTime createdAt
) {}
