package org.angel.paymentclean.infrastructure.adapters.out.persistence.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import org.angel.paymentclean.domain.enums.Role;
import org.angel.paymentclean.domain.util.IdGenerator;

@Entity
@Table(name = "users")
@Getter
@Setter
@AllArgsConstructor
public class UserEntity {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    private String username;
    private String email;
    private String password;
    private String phone;

    @Column(name = "stripe_customer_id", length = 64)
    private String stripeCustomerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 20, nullable = false)
    private Role role = Role.CLIENT;

    public UserEntity() {}

    public UserEntity(String id, String username, String email, String phone, String stripeCustomerId) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.phone = phone;
        this.stripeCustomerId = stripeCustomerId;
        this.role = Role.CLIENT;
    }

    public UserEntity(String id, String username, String email, String password, String phone, String stripeCustomerId) {
        this(id, username, email, password, phone, stripeCustomerId, Role.CLIENT);
    }

    @PrePersist
    public void ensureId() {
        if (this.id == null || this.id.isBlank()) {
            this.id = IdGenerator.userId();
        }
        if (this.role == null) {
            this.role = Role.CLIENT;
        }
    }
}
