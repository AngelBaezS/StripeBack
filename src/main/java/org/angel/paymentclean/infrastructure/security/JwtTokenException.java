package org.angel.paymentclean.infrastructure.security;

import org.springframework.security.core.AuthenticationException;

public class JwtTokenException extends AuthenticationException {

    private final String code;

    public JwtTokenException(String code, String message) {
        super(message);
        this.code = code;
    }

    public JwtTokenException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}

