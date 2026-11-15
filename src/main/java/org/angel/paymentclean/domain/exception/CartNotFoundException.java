package org.angel.paymentclean.domain.exception;

public class CartNotFoundException extends DomainException {
    public CartNotFoundException(String id) {
        super("CART_NOT_FOUND", "No se encontró el carrito con ID: " + id);
    }
}
