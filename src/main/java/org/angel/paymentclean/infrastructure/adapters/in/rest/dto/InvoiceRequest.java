package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InvoiceRequest(
        @NotBlank(message = "El RFC es obligatorio.")
        @Pattern(
                regexp = "^[A-Za-zÑñ&]{3,4}[0-9]{6}[A-Za-z0-9]{3}$",
                message = "El RFC no cumple con el formato válido del SAT (12 o 13 caracteres)."
        )
        String rfc,

        @NotBlank(message = "La razón social o nombre fiscal es obligatoria.")
        @Size(max = 255, message = "La razón social no puede exceder 255 caracteres.")
        String legalName,

        @NotBlank(message = "El código postal fiscal es obligatorio.")
        @Pattern(regexp = "^[0-9]{5}$", message = "El código postal fiscal debe contener exactamente 5 dígitos.")
        String zipCode,

        @NotBlank(message = "El régimen fiscal es obligatorio.")
        @Pattern(regexp = "^[0-9]{3}$", message = "La clave de régimen fiscal SAT debe contener 3 dígitos.")
        String taxRegime,

        @NotBlank(message = "El uso de CFDI es obligatorio.")
        @Pattern(regexp = "^[A-Za-z0-9]{3,4}$", message = "La clave de uso de CFDI no es válida.")
        String cfdiUse,

        @NotBlank(message = "El correo electrónico para envío de factura es obligatorio.")
        @Email(message = "El correo electrónico proporcionado no es válido.")
        String email
) {}
