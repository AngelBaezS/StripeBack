package org.angel.paymentclean.domain.exception;

public class StripeIntegrationException extends DomainException {
    public StripeIntegrationException(String message) {
        super("STRIPE_PAYMENT_ERROR", message);
    }

    public StripeIntegrationException(String message, Throwable cause) {
        super("STRIPE_PAYMENT_ERROR", message, cause);
    }
}
