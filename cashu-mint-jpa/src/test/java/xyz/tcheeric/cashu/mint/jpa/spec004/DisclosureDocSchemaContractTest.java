package xyz.tcheeric.cashu.mint.jpa.spec004;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MerchantDebitFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MerchantIouFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIssuanceEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 004 T103 / SC-003 — guard that
 * {@code docs/explanations/voucher-data-record.md} matches the actual
 * JPA schema. The disclosure document lists every column the mint
 * stores per voucher purchase; if a schema column is added (or removed)
 * without updating the doc, this test fails. This is the customer-trust
 * anchor — operators cannot quietly start recording new data without
 * the disclosure catching up.
 *
 * <p>The test is intentionally one-directional: every JPA
 * {@code @Column} name on the four voucher tables MUST appear in the
 * disclosure doc. The doc is allowed to mention "columns" that are
 * actually documentation labels (e.g. the audit log table is described
 * but not iterated column-by-column).
 */
class DisclosureDocSchemaContractTest {

    private static final Path DISCLOSURE_DOC = Path.of(
            "..", "docs", "explanations", "voucher-data-record.md");

    @Test
    void everyVoucherSchemaColumnIsMentionedInTheDisclosureDoc() throws IOException {
        String doc = Files.readString(DISCLOSURE_DOC.toAbsolutePath().normalize());

        Set<String> schemaColumns = new TreeSet<>();
        schemaColumns.addAll(columnsOf(VoucherQuoteEntity.class));
        schemaColumns.addAll(columnsOf(CustomerPaymentFundingEntity.class));
        schemaColumns.addAll(columnsOf(MerchantDebitFundingEntity.class));
        schemaColumns.addAll(columnsOf(MerchantIouFundingEntity.class));
        schemaColumns.addAll(columnsOf(VoucherIssuanceEntity.class));

        // 'version' is JPA optimistic locking infrastructure, not
        // customer-disclosable data. Exclude.
        schemaColumns.remove("version");

        Set<String> missing = new TreeSet<>();
        for (String col : schemaColumns) {
            if (!doc.contains("`" + col + "`")) {
                missing.add(col);
            }
        }

        assertThat(missing)
                .as("Schema columns missing from docs/explanations/voucher-data-record.md. "
                        + "Add a row for each missing column (with what it is + identity-bearing? Y/N) "
                        + "or remove the column from the entity if it shouldn't be stored.")
                .isEmpty();
    }

    private static Set<String> columnsOf(Class<?> entityClass) {
        Set<String> names = new TreeSet<>();
        Class<?> current = entityClass;
        while (current != null && current != Object.class) {
            for (Field f : current.getDeclaredFields()) {
                Column col = f.getAnnotation(Column.class);
                if (col != null && !col.name().isBlank()) {
                    names.add(col.name());
                }
            }
            current = current.getSuperclass();
        }
        return names;
    }
}
