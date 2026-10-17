package org.angel.paymentclean.domain.exception;

public class InvalidArticleException extends DomainException{
    public InvalidArticleException(String message) {
        super("INVALID_ARTICLE_DATA", message);
    }
}
