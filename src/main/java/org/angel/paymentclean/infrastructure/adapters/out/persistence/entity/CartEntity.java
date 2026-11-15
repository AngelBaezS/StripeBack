package org.angel.paymentclean.infrastructure.adapters.out.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.angel.paymentclean.domain.enums.CartStatus;
import org.angel.paymentclean.domain.util.IdGenerator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "carts")
@Getter
@Setter
public class CartEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private CartStatus status;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<CartItemEntity> items = new ArrayList<>();

    public CartEntity() {}

    public CartEntity(String id, String userId, CartStatus status, LocalDateTime updatedAt) {
        this.id = id;
        this.userId = userId;
        this.status = status != null ? status : CartStatus.ACTIVE;
        this.updatedAt = updatedAt;
    }

    public void addItem(String id, String productId, Integer quantity) {
        this.items.add(new CartItemEntity(id, this, productId, quantity));
    }

    public void clearItems() {
        this.items.clear();
    }

    @PrePersist
    public void ensureId() {
        if (this.id == null || this.id.isBlank()) {
            this.id = IdGenerator.cartId();
        }
        if (this.status == null) {
            this.status = CartStatus.ACTIVE;
        }
    }
}
