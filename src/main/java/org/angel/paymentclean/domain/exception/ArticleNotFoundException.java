package org.angel.paymentclean.domain.exception;

public class ArticleNotFoundException extends DomainException{
    public ArticleNotFoundException(Long id) {
        super("ARTICLE_NOT_FOUND", "No se encontró el artículo con ID: " + id);
    }
}
