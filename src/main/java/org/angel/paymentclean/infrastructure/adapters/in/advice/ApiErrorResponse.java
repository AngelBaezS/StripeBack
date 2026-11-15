package org.angel.paymentclean.infrastructure.adapters.in.advice;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiErrorResponse(
        String code,
        String message,
        int status,
        LocalDateTime timestamp,
        Map<String, String> fieldErrors // Mapa opcional: "nombreCampo" -> "mensaje de error"
) {
    public ApiErrorResponse(String code, String message, int status, LocalDateTime timestamp) {
        this(code, message, status, timestamp, null);
    }
}
