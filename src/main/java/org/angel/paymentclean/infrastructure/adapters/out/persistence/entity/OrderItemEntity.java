package org.angel.paymentclean.infrastructure.adapters.out.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.angel.paymentclean.domain.util.IdGenerator;

@Entity
@Table(name = "order_items")
@Getter
@Setter
public class OrderItemEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private OrderEntity order;

    @Column(name = "product_id", length = 36)
    private String productId;

    @Column(name = "stripe_price_id", length = 64, nullable = false)
    private String stripePriceId;

    @Column(nullable = false)
    private Long quantity;

    public OrderItemEntity() {}

    public OrderItemEntity(String id, OrderEntity order, String productId, String stripePriceId, Long quantity) {
        this.id = id;
        this.order = order;
        this.productId = productId;
        this.stripePriceId = stripePriceId;
        this.quantity = quantity;
    }

    public OrderItemEntity(OrderEntity order, String productId, String stripePriceId, Long quantity) {
        this(null, order, productId, stripePriceId, quantity);
    }

    @PrePersist
    public void ensureId() {
        if (this.id == null || this.id.isBlank()) {
            this.id = IdGenerator.orderItemId();
        }
    }
}
