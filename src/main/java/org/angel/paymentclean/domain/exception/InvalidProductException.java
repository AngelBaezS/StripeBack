package org.angel.paymentclean.domain.exception;

public class InvalidProductException extends DomainException{
    public InvalidProductException(String message) {
        super("INVALID_ARTICLE_DATA", message);
    }
}
