package org.angel.paymentclean.infrastructure.adapters.in.advice;

import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.exception.CartNotFoundException;
import org.angel.paymentclean.domain.exception.CfdiIntegrationException;
import org.angel.paymentclean.domain.exception.ProductNotFoundException;
import org.angel.paymentclean.domain.exception.DomainException;
import org.angel.paymentclean.domain.exception.InvalidProductException;
import org.angel.paymentclean.domain.exception.StripeIntegrationException;
import org.angel.paymentclean.domain.exception.UserNotFoundException;
import org.angel.paymentclean.infrastructure.security.JwtTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 1. Captura TODAS las DomainException en un solo lugar
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainException(DomainException ex) {
        HttpStatus status = resolveHttpStatus(ex);
        ApiErrorResponse response = new ApiErrorResponse(
                ex.getCode(),
                ex.getMessage(),
                status.value(),
                LocalDateTime.now());
        return ResponseEntity.status(status).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }

        ApiErrorResponse response = new ApiErrorResponse(
                "INVALID_REQUEST_PARAMETERS",
                "Uno o más campos de la petición tienen errores de formato.",
                HttpStatus.BAD_REQUEST.value(),
                LocalDateTime.now(),
                errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(JwtTokenException.class)
    public ResponseEntity<ApiErrorResponse> handleJwtTokenException(JwtTokenException ex) {
        ApiErrorResponse response = new ApiErrorResponse(
                ex.getCode(),
                ex.getMessage(),
                HttpStatus.UNAUTHORIZED.value(),
                LocalDateTime.now());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentials(BadCredentialsException ex) {
        ApiErrorResponse response = new ApiErrorResponse(
                "INVALID_CREDENTIALS",
                "Credenciales inválidas",
                HttpStatus.UNAUTHORIZED.value(),
                LocalDateTime.now());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationException(AuthenticationException ex) {
        ApiErrorResponse response = new ApiErrorResponse(
                "UNAUTHORIZED",
                "Autenticación requerida.",
                HttpStatus.UNAUTHORIZED.value(),
                LocalDateTime.now());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDeniedException(AccessDeniedException ex) {
        ApiErrorResponse response = new ApiErrorResponse(
                "FORBIDDEN",
                "No tienes permisos para acceder a este recurso.",
                HttpStatus.FORBIDDEN.value(),
                LocalDateTime.now());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    // 2. Mapeo limpio usando Pattern Matching para switch (Java 21)
    private HttpStatus resolveHttpStatus(DomainException ex) {
        return switch (ex) {
            case ProductNotFoundException e -> HttpStatus.NOT_FOUND; // 404
            case UserNotFoundException e -> HttpStatus.NOT_FOUND; // 404
            case CartNotFoundException e -> HttpStatus.NOT_FOUND; // 404
            case InvalidProductException e -> HttpStatus.UNPROCESSABLE_CONTENT; // 422
            case ArticleBusinessConflictException e -> HttpStatus.CONFLICT; // 409
            case StripeIntegrationException e -> HttpStatus.BAD_GATEWAY; // 502
            case CfdiIntegrationException e -> HttpStatus.BAD_GATEWAY; // 502
            default -> HttpStatus.BAD_REQUEST; // 400 por defecto
        };
    }

    // 3. Fallback para errores inesperados (NullPointer, caída de BD, etc.)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception ex) {
        log.error("Error interno no controlado en endpoint REST: {}", ex.getMessage(), ex);
        ApiErrorResponse response = new ApiErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "Ocurrió un error interno inesperado.",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                LocalDateTime.now());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
