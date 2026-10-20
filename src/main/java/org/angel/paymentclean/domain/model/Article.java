package org.angel.paymentclean.domain.model;

import java.math.BigDecimal;

import org.angel.paymentclean.domain.exception.InvalidArticleException;

public record Article(
        Long id,
        String description,
        String imageUrl,
        BigDecimal unitPrice,
        String claveProdServ,
        String claveUnidad,
        boolean active) 
{
    public Article {
        if (description == null || description.isBlank() || description.isEmpty()) {
            throw new InvalidArticleException("La descripción no puede estar vacía");
        }
        if (imageUrl == null || imageUrl.isBlank() || imageUrl.isEmpty()) {
            throw new InvalidArticleException("La imagen debe ser una URL válida");
        }
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidArticleException("El precio unitario debe ser mayor a cero");
        }
        if (claveProdServ == null || claveProdServ.isEmpty()) {
            throw new InvalidArticleException("La clave de producto o servicio no puede estar vacía");
        }
        if (claveUnidad == null || claveUnidad.isEmpty()) {
            throw new InvalidArticleException("La clave de unidad no puede estar vacía");
        }
    }
}
