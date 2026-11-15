package org.angel.paymentclean.domain.model;

import java.time.LocalDateTime;

public record SpeiPaymentDetails(
        String clabe,
        String bankName,
        String bankCode,
        String reference,
        String hostedInstructionsUrl,
        Long amountCents,
        String currency,
        String orderId,
        String recipientEmail,
        LocalDateTime expiresAt
) {
    public SpeiPaymentDetails(
            String clabe,
            String bankName,
            String bankCode,
            String reference,
            String hostedInstructionsUrl,
            Long amountCents,
            String currency
    ) {
        this(clabe, bankName, bankCode, reference, hostedInstructionsUrl, amountCents, currency, null, null, null);
    }

    public SpeiPaymentDetails withOrderContext(String orderId, String recipientEmail, LocalDateTime expiresAt) {
        return new SpeiPaymentDetails(
                clabe,
                bankName,
                bankCode,
                reference,
                hostedInstructionsUrl,
                amountCents,
                currency,
                orderId,
                recipientEmail,
                expiresAt
        );
    }
}

