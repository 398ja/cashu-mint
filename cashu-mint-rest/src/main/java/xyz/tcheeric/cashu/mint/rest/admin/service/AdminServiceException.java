package xyz.tcheeric.cashu.mint.rest.admin.service;

import org.springframework.http.HttpStatus;

import java.util.Objects;

/**
 * Signals a domain or validation failure while executing an administrative workflow.
 */
public class AdminServiceException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AdminServiceException(final HttpStatus status, final String code, final String message) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
        this.code = Objects.requireNonNull(code, "code");
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
