package xyz.tcheeric.cashu.mint.admin.rest.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import xyz.tcheeric.cashu.mint.admin.rest.dto.common.AdminErrorResponse;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminServiceException;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures ResponseStatusExceptions raised by the admin API propagate their status codes.
 */
@RestControllerAdvice(basePackages = "xyz.tcheeric.cashu.mint.admin.rest")
@Order(Ordered.HIGHEST_PRECEDENCE)
class AdminApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(AdminApiExceptionHandler.class);

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<AdminErrorResponse> onMethodArgumentNotValid(final MethodArgumentNotValidException ex) {
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        final String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request payload");
        final AdminErrorResponse body = new AdminErrorResponse(status.value(), status.getReasonPhrase(),
                "invalid_request", message);
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<AdminErrorResponse> onIllegalArgument(final IllegalArgumentException ex) {
        final HttpStatus status = HttpStatus.BAD_REQUEST;
        final AdminErrorResponse body = new AdminErrorResponse(status.value(), status.getReasonPhrase(),
                "invalid_request", ex.getMessage() == null ? status.getReasonPhrase() : ex.getMessage());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<AdminErrorResponse> onUnhandledException(final Exception ex) {
        LOG.error("Unhandled exception in admin API", ex);
        final HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        final AdminErrorResponse body = new AdminErrorResponse(status.value(), status.getReasonPhrase(),
                "internal_error", "An unexpected error occurred");
        return ResponseEntity.status(status).body(body);
    }
}
