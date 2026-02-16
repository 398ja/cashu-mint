package xyz.tcheeric.cashu.mint.admin.adapter.out.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

final class TranslatingDataSource implements DataSource {

    private final DataSource delegate;

    TranslatingDataSource(final DataSource delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate data source must not be null");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(final String username, final String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(final PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(final int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(TranslatingDataSource.class.getName());
    }

    @Override
    public <T> T unwrap(final Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(final Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }

    private Connection wrap(final Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
            new SqlTranslatingInvocationHandler(connection));
    }

    private static final class SqlTranslatingInvocationHandler implements InvocationHandler {

        private final Connection delegate;

        SqlTranslatingInvocationHandler(final Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            if ("prepareStatement".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof String sql) {
                args[0] = translate(sql);
            }
            try {
                return method.invoke(delegate, args);
            } catch (final InvocationTargetException ex) {
                throw ex.getCause();
            }
        }

        private String translate(final String sql) {
            if (sql.contains("INSERT INTO configuration_revisions")) {
                return """
                    MERGE INTO configuration_revisions (mint_id, revision_id, parameters, audit_actor, audit_action, audit_timestamp)
                    KEY (mint_id, revision_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;
            }
            if (sql.contains("INSERT INTO mints")) {
                return """
                    MERGE INTO mints (mint_id, lifecycle_state, current_configuration_revision, last_actor, last_action, last_timestamp, version)
                    KEY (mint_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
            }
            if (sql.contains("INSERT INTO operator_accounts")) {
                return """
                    MERGE INTO operator_accounts (mint_id, operator_id, display_name, roles, audit_actor, audit_action, audit_timestamp)
                    KEY (mint_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
            }
            if (sql.contains("INSERT INTO notification_policies")) {
                return """
                    MERGE INTO notification_policies (mint_id, email_enabled, webhook_enabled, throttle_interval_seconds, audit_actor, audit_action, audit_timestamp)
                    KEY (mint_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """;
            }
            return sql;
        }
    }
}
