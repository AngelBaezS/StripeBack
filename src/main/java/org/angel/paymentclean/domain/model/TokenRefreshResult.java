package org.angel.paymentclean.domain.model;

import org.angel.paymentclean.domain.enums.Role;

import java.time.Instant;

public record TokenRefreshResult(
        String token,
        Role role,
        Instant expiresAt,
        boolean refreshed
) {
}

