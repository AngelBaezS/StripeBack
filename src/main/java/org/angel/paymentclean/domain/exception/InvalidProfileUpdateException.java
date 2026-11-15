package org.angel.paymentclean.domain.exception;

public class InvalidProfileUpdateException extends DomainException {

    public InvalidProfileUpdateException(String code, String message) {
        super(code, message);
    }
}

