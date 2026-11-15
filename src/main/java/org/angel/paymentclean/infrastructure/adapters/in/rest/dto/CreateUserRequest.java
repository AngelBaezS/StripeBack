package org.angel.paymentclean.infrastructure.adapters.in.rest.dto;

public record CreateUserRequest(
        String username,
        String email,
        String password,
        String phone,
        String role
) {
    public CreateUserRequest(String username, String email, String password, String phone) {
        this(username, email, password, phone, null);
    }
}
