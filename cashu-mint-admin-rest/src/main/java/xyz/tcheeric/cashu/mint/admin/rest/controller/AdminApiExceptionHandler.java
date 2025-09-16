package xyz.tcheeric.cashu.mint.admin.rest.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.AdminErrorResponse;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminServiceException;

import java.util.Locale;

/**
 * Ensures ResponseStatusExceptions raised by the admin API propagate their status codes.
 */
@RestControllerAdvice(basePackages = "xyz.tcheeric.cashu.mint.admin.rest")
@Order(Ordered.HIGHEST_PRECEDENCE)
class AdminApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<AdminErrorResponse> onResponseStatusException(final ResponseStatusException ex) {
        final int statusCode = ex.getStatusCode().value();
        final HttpStatus status = HttpStatus.resolve(statusCode);
        final String error = status != null ? status.getReasonPhrase() : ex.getStatusCode().toString();
        final String code = status != null ? status.name().toLowerCase(Locale.ROOT) : "error";
        final String message = ex.getReason() != null && !ex.getReason().isBlank() ? ex.getReason() : error;
        final AdminErrorResponse body = new AdminErrorResponse(statusCode, error, code, message);
        return ResponseEntity.status(statusCode).body(body);
    }

    @ExceptionHandler(AdminServiceException.class)
    ResponseEntity<AdminErrorResponse> onAdminServiceException(final AdminServiceException ex) {
        final HttpStatus status = ex.getStatus();
        final AdminErrorResponse body = new AdminErrorResponse(status.value(), status.getReasonPhrase(), ex.getCode(), ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<AdminErrorResponse> onIllegalArgument(final IllegalArgumentException ex) {
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        final AdminErrorResponse body = new AdminErrorResponse(status.value(), status.getReasonPhrase(),
                "invalid_request", ex.getMessage() == null ? status.getReasonPhrase() : ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
