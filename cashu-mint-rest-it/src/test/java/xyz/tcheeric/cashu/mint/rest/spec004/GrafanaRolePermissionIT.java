package xyz.tcheeric.cashu.mint.rest.spec004;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.rest.spec003.support.AbstractVoucherDurableIT;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 004 T610 / SC-012 / Clarifications Q4 — verifies the
 * cashu_mint_grafana_ro PostgreSQL role cannot read identity columns.
 * Defence in depth on top of dashboard JSON review: even if a Grafana
 * editor writes an ad-hoc query that selects {@code customer_id}, the
 * database refuses at the GRANT layer.
 */
class GrafanaRolePermissionIT extends AbstractVoucherDurableIT {

    private static final String GRAFANA_RO_PASSWORD = "it-grafana-ro-password";

    @Test
    void grafanaRoCannotSelectCustomerId() {
        try (Connection conn = grafanaRoConnection()) {
            assertThatThrownBy(() -> {
                try (Statement s = conn.createStatement()) {
                    s.executeQuery("SELECT customer_id FROM voucher_quote LIMIT 1");
                }
            }).hasMessageContaining("permission denied for");
        } catch (SQLException e) {
            throw new AssertionError("Failed to open cashu_mint_grafana_ro connection", e);
        }
    }

    @Test
    void grafanaRoCannotSelectMerchantId() {
        try (Connection conn = grafanaRoConnection()) {
            assertThatThrownBy(() -> {
                try (Statement s = conn.createStatement()) {
                    s.executeQuery("SELECT merchant_id FROM voucher_quote LIMIT 1");
                }
            }).hasMessageContaining("permission denied for");
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void grafanaRoCannotSelectFundingCustomerId() {
        try (Connection conn = grafanaRoConnection()) {
            assertThatThrownBy(() -> {
                try (Statement s = conn.createStatement()) {
                    s.executeQuery("SELECT customer_id FROM customer_payment_funding LIMIT 1");
                }
            }).hasMessageContaining("permission denied for");
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void grafanaRoCanSelectFinancialColumns() {
        // Sanity: the role IS granted SELECT on the financial columns
        // the dashboards actually use.
        try (Connection conn = grafanaRoConnection()) {
            try (Statement s = conn.createStatement()) {
                var rs = s.executeQuery(
                        "SELECT quote_id, face_value, lifecycle_state FROM voucher_quote LIMIT 1");
                assertThat(rs.next() || true).isTrue(); // smoke; absence of rows is OK
            }
        } catch (SQLException e) {
            throw new AssertionError("cashu_mint_grafana_ro should be able to SELECT financial columns", e);
        }
    }

    private Connection grafanaRoConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "cashu_mint_grafana_ro", GRAFANA_RO_PASSWORD);
    }
}
