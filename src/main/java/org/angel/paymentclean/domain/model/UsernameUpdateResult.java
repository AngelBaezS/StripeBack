package org.angel.paymentclean.domain.model;

public record UsernameUpdateResult(
        User user,
        String token
) {
}

