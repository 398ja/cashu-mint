package xyz.tcheeric.cashu.mint.jpa.service;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.ports.IdentityHasher;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * AppSec finding L-2 (issue #429): the identity backfill interpolates table and column names into
 * SQL, so the set of identifiers it will accept must be stated at the method boundary.
 *
 * <p>Neither a table nor a column can be a bind parameter, so the interpolation itself is
 * unavoidable. What was avoidable is that {@code hashOneBatch} is public, takes three
 * {@code String} identifiers, and is re-exposed by {@code VoucherIdentityBackfillService} as
 * another public overload — one careless caller away from injection, with nothing at the boundary
 * saying so.
 *
 * <p>These tests also assert that a refused call touches no {@code DataSource}, because a
 * rejection that happens after the statement is built would not be much of a control.
 */
class VoucherIdentityBackfillBatchAllowlistTest {

    private static final int BATCH_SIZE = 100;

    /** A table outside the allowlist is refused before any SQL is built. */
    @Test
    void anUnknownTableIsRefused() {
        DataSource dataSource = mock(DataSource.class);
        VoucherIdentityBackfillBatch batch = new VoucherIdentityBackfillBatch(dataSource);

        assertThatThrownBy(() -> batch.hashOneBatch(
                "users", "users_aud", "customer_id", hasher(), BATCH_SIZE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a permitted table");

        verifyNoInteractions(dataSource);
    }

    /** A column outside the allowlist is refused. */
    @Test
    void anUnknownColumnIsRefused() {
        DataSource dataSource = mock(DataSource.class);
        VoucherIdentityBackfillBatch batch = new VoucherIdentityBackfillBatch(dataSource);

        assertThatThrownBy(() -> batch.hashOneBatch(
                "voucher_quote", "voucher_quote_aud", "password_hash", hasher(), BATCH_SIZE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a permitted column");

        verifyNoInteractions(dataSource);
    }

    /**
     * The injection shape the allowlist exists to stop: an identifier carrying SQL rather than
     * naming a column.
     */
    @Test
    void anInjectionAttemptInTheColumnNameIsRefused() {
        DataSource dataSource = mock(DataSource.class);
        VoucherIdentityBackfillBatch batch = new VoucherIdentityBackfillBatch(dataSource);

        assertThatThrownBy(() -> batch.hashOneBatch(
                "voucher_quote", "voucher_quote_aud",
                "customer_id FROM voucher_quote; DROP TABLE voucher_quote; --",
                hasher(), BATCH_SIZE))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(dataSource);
    }

    /** The same for the table position. */
    @Test
    void anInjectionAttemptInTheTableNameIsRefused() {
        DataSource dataSource = mock(DataSource.class);
        VoucherIdentityBackfillBatch batch = new VoucherIdentityBackfillBatch(dataSource);

        assertThatThrownBy(() -> batch.hashOneBatch(
                "voucher_quote; DROP TABLE voucher_quote; --", "voucher_quote_aud",
                "customer_id", hasher(), BATCH_SIZE))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(dataSource);
    }

    /** A null identifier is refused rather than producing a malformed statement. */
    @Test
    void aNullIdentifierIsRefused() {
        DataSource dataSource = mock(DataSource.class);
        VoucherIdentityBackfillBatch batch = new VoucherIdentityBackfillBatch(dataSource);

        assertThatThrownBy(() -> batch.hashOneBatch(
                null, "voucher_quote_aud", "customer_id", hasher(), BATCH_SIZE))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(dataSource);
    }

    /**
     * Every identifier the production caller actually uses passes the allowlist.
     *
     * <p>Without this, an allowlist that refused everything would satisfy the tests above while
     * silently disabling the backfill.
     *
     * <p>The identifiers are read out of {@link VoucherIdentityBackfillService} by reflection
     * rather than restated here. A hand-copied list is only correct on the day it is written: add
     * a table to the service and a literal list still passes while the backfill fails at runtime
     * on the new one. Reading the real maps means adding a table without allowlisting it fails
     * here instead.
     */
    @Test
    void everyIdentifierTheProductionCallerUsesIsPermitted() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        VoucherIdentityBackfillBatch batch = new VoucherIdentityBackfillBatch(dataSource);

        String[][] realCalls = productionIdentifiers();
        assertThat(realCalls).as("no identifiers discovered; reflection lookup is broken")
                .isNotEmpty();

        for (String[] call : realCalls) {
            // Passing the allowlist means execution reaches the JdbcTemplate, which then fails on
            // the mock DataSource. Any IllegalArgumentException here would mean the allowlist
            // rejected a legitimate identifier.
            assertThatThrownBy(() -> batch.hashOneBatch(call[0], call[1], call[2], hasher(), BATCH_SIZE))
                    .as("%s.%s must be permitted", call[0], call[2])
                    .isNotInstanceOf(IllegalArgumentException.class);
        }
    }

    private static IdentityHasher hasher() {
        return value -> "0".repeat(64);
    }

    /**
     * The (live, aud, column) triples {@code VoucherIdentityBackfillService} will actually pass.
     *
     * <p>Read from the service's own static maps so this test cannot drift away from the code it
     * is meant to protect.
     */
    @SuppressWarnings("unchecked")
    private static String[][] productionIdentifiers() throws Exception {
        Map<String, List<String>> liveTables =
                (Map<String, List<String>>) readStaticField("LIVE_TABLES");
        Map<String, String> audOf = (Map<String, String>) readStaticField("AUD_OF");

        List<String[]> triples = new ArrayList<>();
        liveTables.forEach((liveTable, columns) -> columns.forEach(
                column -> triples.add(new String[] {liveTable, audOf.get(liveTable), column})));
        return triples.toArray(new String[0][]);
    }

    private static Object readStaticField(String name) throws Exception {
        Field field = VoucherIdentityBackfillService.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }
}
