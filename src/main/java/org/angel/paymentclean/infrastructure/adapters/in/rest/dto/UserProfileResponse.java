package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

import org.angel.paymentclean.domain.enums.Role;

public record UserProfileResponse(
        String id,
        String username,
        String email,
        String phone,
        Role role
) {
}

