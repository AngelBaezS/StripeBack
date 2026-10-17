package org.angel.paymentclean.infrastructure.adapters.in.advice;

import org.angel.paymentclean.domain.exception.ArticleBusinessConflictException;
import org.angel.paymentclean.domain.exception.ArticleNotFoundException;
import org.angel.paymentclean.domain.exception.DomainException;
import org.angel.paymentclean.domain.exception.InvalidArticleException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // 1. Captura TODAS las DomainException en un solo lugar
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainException(MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }

        ApiErrorResponse response = new ApiErrorResponse(
                "INVALID_REQUEST_PARAMETERS",
                "Uno o más campos de la petición tienen errores de formato.",
                HttpStatus.BAD_REQUEST.value(),
                LocalDateTime.now(),
                errors
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // 2. Mapeo limpio usando Pattern Matching para switch (Java 17+ / 21)
    private HttpStatus resolveHttpStatus(DomainException ex) {
        return switch (ex) {
            case ArticleNotFoundException e -> HttpStatus.NOT_FOUND; // 404
            case InvalidArticleException e -> HttpStatus.UNPROCESSABLE_ENTITY; // 422 (o 400 BAD_REQUEST)
            case ArticleBusinessConflictException e -> HttpStatus.CONFLICT; // 409
            default -> HttpStatus.BAD_REQUEST; // 400 por defecto para cualquier otra DomainException
        };
    }

    // 3. Fallback para errores inesperados (NullPointer, caída de BD, etc.)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception ex) {
        ApiErrorResponse response = new ApiErrorResponse(
                "INTERNAL_SERVER_ERROR",
                "Ocurrió un error interno inesperado.",
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                LocalDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
