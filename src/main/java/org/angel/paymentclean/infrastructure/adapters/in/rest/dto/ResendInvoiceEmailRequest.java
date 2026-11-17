package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import jakarta.validation.constraints.Email;

public record ResendInvoiceEmailRequest(
        @Email(message = "El correo electrónico proporcionado no es válido.")
        String email
) {}
