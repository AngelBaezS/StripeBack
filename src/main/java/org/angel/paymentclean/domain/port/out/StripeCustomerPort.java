package org.angel.paymentclean.domain.port.out;

public interface StripeCustomerPort {
    String crearClienteEnStripe(String nombre, String email, String telefono);
}
