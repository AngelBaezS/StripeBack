package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CreateArticleRequest(
        @NotBlank(message = "La descripción no puede estar vacía")
        String description,

        String imageUrl,

        @NotNull(message = "El precio unitario es obligatorio")
        @Positive(message = "El precio unitario debe ser mayor a cero")
        BigDecimal unitPrice,

        @NotBlank(message = "La clave de producto/servicio es obligatoria")
        @Size(min = 8, max = 8, message = "La claveProdServ debe tener exactamente 8 caracteres")
        String claveProdServ,

        @NotBlank(message = "La clave de unidad es obligatoria")
        String claveUnidad
) {}
