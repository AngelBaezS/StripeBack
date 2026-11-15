package org.angel.paymentclean.infrastructure.adapters.out.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.angel.paymentclean.domain.util.IdGenerator;

@Entity
@Table(name = "product")
@Getter
@Setter
public class ProductEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    private String name;
    private String description;
    private Integer stock;
    private Boolean active;

    @Column(name = "stripe_product_id", length = 64)
    private String stripeProductId;

    @Column(name = "stripe_price_id", length = 64)
    private String stripePriceId;

    @Column(name = "unit_amount_cents")
    private Long unitAmountCents;

    @Column(length = 3)
    private String currency;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    public ProductEntity() {}

    public ProductEntity(String id, String name, String description, Integer stock, Boolean active,
                         String stripeProductId, String stripePriceId, Long unitAmountCents, String currency) {
        this(id, name, description, stock, active, stripeProductId, stripePriceId, unitAmountCents, currency, null);
    }

    public ProductEntity(String id, String name, String description, Integer stock, Boolean active,
                         String stripeProductId, String stripePriceId, Long unitAmountCents, String currency, String imageUrl) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.stock = stock;
        this.active = active;
        this.stripeProductId = stripeProductId;
        this.stripePriceId = stripePriceId;
        this.unitAmountCents = unitAmountCents;
        this.currency = currency;
        this.imageUrl = imageUrl;
    }

    @PrePersist
    public void ensureId() {
        if (this.id == null || this.id.isBlank()) {
            this.id = IdGenerator.productId();
        }
    }
}
