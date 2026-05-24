package xyz.tcheeric.cashu.mint.jpa.spec004;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 004 T611 / T612 / T614 — contract tests over the voucher
 * Grafana dashboard JSON files. Single-file test that covers:
 *
 * <ul>
 *   <li><b>T611 / SC-007</b>: every dashboard JSON references at least
 *       one retained financial column (catches "dashboard exists but
 *       reads nothing useful" drift).</li>
 *   <li><b>T612 / SC-008</b>: no dashboard panel query selects an
 *       identity column ({@code customer_id} or {@code merchant_id}).
 *       The cashu_mint_grafana_ro DB role would refuse the query at
 *       runtime, but failing in CI is faster than failing in prod.</li>
 *   <li><b>T614 / SC-010</b>: no dashboard JSON references the
 *       deprecated plural metric name {@code cashu_mint_vouchers_*}
 *       (spec 003 PR #321 used singular; FR-017 reconciles).</li>
 * </ul>
 */
class DashboardContractTest {

    private static final Path DASHBOARD_DIR = Path.of(
            "..", "cashu-mint-observability", "docker", "grafana", "dashboards");

    private static final List<String> VOUCHER_DASHBOARDS = List.of(
            "voucher-liability-overview.json",
            "voucher-token-integrity.json",
            "voucher-iou-liability.json");

    private static final List<String> RETAINED_FINANCIAL_COLUMNS = List.of(
            "face_value", "charged_amount", "fee", "lifecycle_state",
            "funding_source", "amount", "funding_id", "iou_id", "iou_due_at",
            "policy_profile");

    private static final List<String> IDENTITY_COLUMNS = List.of(
            "customer_id", "merchant_id");

    @Test
    void everyVoucherDashboardReferencesAtLeastOneRetainedFinancialColumn() throws IOException {
        for (String name : VOUCHER_DASHBOARDS) {
            String json = readDashboard(name);
            boolean any = RETAINED_FINANCIAL_COLUMNS.stream().anyMatch(json::contains);
            assertThat(any)
                    .as("%s references no retained financial column — empty or stale dashboard", name)
                    .isTrue();
        }
    }

    @Test
    void noDashboardSelectsIdentityColumn() throws IOException {
        for (String name : VOUCHER_DASHBOARDS) {
            String json = readDashboard(name);
            for (String identityCol : IDENTITY_COLUMNS) {
                // Allow the column name to appear in a Javadoc-style
                // description or comment; disallow only when it sits
                // in a SQL clause. The cashu_mint_grafana_ro role
                // would fail the query at runtime regardless, but
                // catching here surfaces the violation before deploy.
                String selectFragment = "SELECT " + identityCol;
                String selectQualified = "." + identityCol + " ";
                String selectInPanel = "\"" + identityCol + "\":";
                assertThat(json)
                        .as("%s SELECTs identity column %s", name, identityCol)
                        .doesNotContainIgnoringCase(selectFragment)
                        .doesNotContainIgnoringCase(selectQualified)
                        .doesNotContainIgnoringCase(selectInPanel);
            }
        }
    }

    @Test
    void noDashboardReferencesDeprecatedPluralMetricName() throws IOException {
        for (String name : VOUCHER_DASHBOARDS) {
            String json = readDashboard(name);
            // Spec 004 FR-017 — singular form only (cashu_mint_voucher_*),
            // not plural (cashu_mint_vouchers_*).
            assertThat(json)
                    .as("%s references deprecated plural metric name", name)
                    .doesNotContain("cashu_mint_vouchers_");
        }
    }

    private static String readDashboard(String name) throws IOException {
        return Files.readString(DASHBOARD_DIR.resolve(name).toAbsolutePath().normalize());
    }
}
