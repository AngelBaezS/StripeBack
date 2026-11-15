package org.angel.paymentclean.infrastructure.adapters.out.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.angel.paymentclean.domain.util.IdGenerator;

@Entity
@Table(name = "cart_items")
@Getter
@Setter
public class CartItemEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private CartEntity cart;

    @Column(name = "product_id", length = 36, nullable = false)
    private String productId;

    @Column(nullable = false)
    private Integer quantity;

    public CartItemEntity() {}

    public CartItemEntity(String id, CartEntity cart, String productId, Integer quantity) {
        this.id = id;
        this.cart = cart;
        this.productId = productId;
        this.quantity = quantity;
    }

    @PrePersist
    public void ensureId() {
        if (this.id == null || this.id.isBlank()) {
            this.id = IdGenerator.cartItemId();
        }
    }
}
