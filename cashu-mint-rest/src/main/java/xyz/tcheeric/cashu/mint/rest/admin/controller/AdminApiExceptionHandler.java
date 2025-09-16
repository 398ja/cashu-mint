package xyz.tcheeric.cashu.mint.rest.admin.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ensures ResponseStatusExceptions raised by the admin API propagate their status codes.
 */
@RestControllerAdvice(basePackages = "xyz.tcheeric.cashu.mint.rest.admin")
@Order(Ordered.HIGHEST_PRECEDENCE)
class AdminApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> onResponseStatusException(ResponseStatusException ex) {
        final Map<String, Object> body = new LinkedHashMap<>();
        final int statusCode = ex.getStatusCode().value();
        body.put("status", statusCode);
        final org.springframework.http.HttpStatus status = org.springframework.http.HttpStatus.resolve(statusCode);
        body.put("error", status != null ? status.getReasonPhrase() : ex.getStatusCode().toString());
        if (ex.getReason() != null && !ex.getReason().isBlank()) {
            body.put("message", ex.getReason());
        }
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }
}
