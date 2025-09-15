package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

/**
 * Runtime wrapper used for propagating JDBC failures through repository boundaries.
 */
public class JdbcRepositoryException extends RuntimeException {

    public JdbcRepositoryException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public JdbcRepositoryException(final String message) {
        super(message);
    }
}
