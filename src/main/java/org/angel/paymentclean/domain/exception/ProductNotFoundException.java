package org.angel.paymentclean.domain.exception;

public class ProductNotFoundException extends DomainException {
    public ProductNotFoundException(String id) {
        super("PRODUCT_NOT_FOUND", "No se encontró el producto con ID: " + id);
    }
}
