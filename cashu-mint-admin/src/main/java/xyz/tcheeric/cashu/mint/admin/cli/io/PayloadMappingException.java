package xyz.tcheeric.cashu.mint.admin.cli.io;

/**
 * Signals a failure while mapping a payload into a command request object.
 */
public class PayloadMappingException extends RuntimeException {

    public PayloadMappingException(final String message) {
        super(message);
    }

    public PayloadMappingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
