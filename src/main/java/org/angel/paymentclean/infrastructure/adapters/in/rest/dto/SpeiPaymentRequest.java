package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import jakarta.validation.constraints.Email;

public record SpeiPaymentRequest(
        @Email(message = "El correo electrónico no tiene un formato válido.")
        String email
) {
}

