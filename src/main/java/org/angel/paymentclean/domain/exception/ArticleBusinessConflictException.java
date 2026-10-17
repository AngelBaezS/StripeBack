package org.angel.paymentclean.domain.exception;

public class ArticleBusinessConflictException extends DomainException{
    public ArticleBusinessConflictException(String message) {
        super("ARTICLE_CONFLICT", message);
    }
}
