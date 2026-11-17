package org.angel.paymentclean.domain.exception;

public class CfdiIntegrationException extends DomainException {
    public CfdiIntegrationException(String message) {
        super("CFDI_EXPRESS_ERROR", message);
    }

    public CfdiIntegrationException(String message, Throwable cause) {
        super("CFDI_EXPRESS_ERROR", message, cause);
    }
}
