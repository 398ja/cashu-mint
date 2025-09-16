package xyz.tcheeric.cashu.mint.admin.cli.io;

/**
 * Signals a rendering failure when transforming a response into console output.
 */
public class ResponseRenderingException extends RuntimeException {

    public ResponseRenderingException(final String message) {
        super(message);
    }

    public ResponseRenderingException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
