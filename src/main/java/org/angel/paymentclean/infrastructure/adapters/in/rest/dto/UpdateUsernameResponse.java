package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

public record UpdateUsernameResponse(
        UserProfileResponse profile,
        String token
) {
}

