package org.angel.paymentclean.domain.exception;

public class UserNotFoundException extends DomainException{
    public UserNotFoundException() {
        super("USER_NOT_FOUND", "Usuario no encontrado");
    }
}
