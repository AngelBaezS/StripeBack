package org.angel.paymentclean.domain.model;

import org.angel.paymentclean.domain.enums.Role;

public record User(
        String id,
        String username,
        String email,
        String password,
        String phone,
        String stripeCustomerId,
        Role role
) {
    public User {
        if (role == null) {
            role = Role.CLIENT;
        }
    }

    public User(String id, String username, String email, String password, String phone, String stripeCustomerId) {
        this(id, username, email, password, phone, stripeCustomerId, Role.CLIENT);
    }
}
