package org.angel.paymentclean.domain.model;

import lombok.*;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Article {
    private Long id;
    private String description;
    private String imageUrl;
    private BigDecimal unitPrice;
    private String claveProdServ;
    private String claveUnidad;
    private boolean active;
}
