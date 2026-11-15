package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import org.angel.paymentclean.domain.enums.Role;

import java.time.Instant;

public record RefreshTokenResponse(
        String token,
        Role role,
        Instant expiresAt,
        boolean refreshed
) {
}

