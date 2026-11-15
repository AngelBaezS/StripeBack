package org.angel.paymentclean.infrastructure.adapters.out.persistence.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.angel.paymentclean.domain.enums.Status;
import org.angel.paymentclean.domain.util.IdGenerator;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@Setter
public class OrderEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "cart_id", length = 36)
    private String cartId;

    @Column(name = "stripe_customer_id", length = 255)
    private String stripeCustomerId;

    @Column(name = "stripe_payment_intent_id", unique = true, length = 255)
    private String stripePaymentIntentId;

    @Column(name = "stripe_checkout_session_id", length = 255)
    private String stripeCheckoutSessionId;

    @Column(name = "stripe_payment_link_id", length = 255)
    private String stripePaymentLinkId;

    @Column(name = "payment_url", columnDefinition = "TEXT")
    private String paymentUrl;

    @Column(name = "total_amount_cents")
    private Long totalAmountCents;

    @Column(length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Status status;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderItemEntity> items = new ArrayList<>();

    public OrderEntity() {}

    public OrderEntity(String id, String userId, String cartId, String stripeCustomerId, String stripePaymentIntentId,
                       String stripeCheckoutSessionId, String stripePaymentLinkId, String paymentUrl,
                       Long totalAmountCents, String currency, Status status) {
        this(id, userId, cartId, stripeCustomerId, stripePaymentIntentId, stripeCheckoutSessionId,
                stripePaymentLinkId, paymentUrl, totalAmountCents, currency, status, null);
    }

    public OrderEntity(String id, String userId, String cartId, String stripeCustomerId, String stripePaymentIntentId,
                       String stripeCheckoutSessionId, String stripePaymentLinkId, String paymentUrl,
                       Long totalAmountCents, String currency, Status status, LocalDateTime createdAt) {
        this(id, userId, cartId, stripeCustomerId, stripePaymentIntentId, stripeCheckoutSessionId,
                stripePaymentLinkId, paymentUrl, totalAmountCents, currency, status, createdAt, null);
    }

    public OrderEntity(String id, String userId, String cartId, String stripeCustomerId, String stripePaymentIntentId,
                       String stripeCheckoutSessionId, String stripePaymentLinkId, String paymentUrl,
                       Long totalAmountCents, String currency, Status status, LocalDateTime createdAt, LocalDateTime canceledAt) {
        this.id = id;
        this.userId = userId;
        this.cartId = cartId;
        this.stripeCustomerId = stripeCustomerId;
        this.stripePaymentIntentId = stripePaymentIntentId;
        this.stripeCheckoutSessionId = stripeCheckoutSessionId;
        this.stripePaymentLinkId = stripePaymentLinkId;
        this.paymentUrl = paymentUrl;
        this.totalAmountCents = totalAmountCents;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
        this.canceledAt = canceledAt;
    }

    public void addItem(String productId, String stripePriceId, Long quantity) {
        this.items.add(new OrderItemEntity(this, productId, stripePriceId, quantity));
    }

    @PrePersist
    public void onPrePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = IdGenerator.orderId();
        }
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }
}
