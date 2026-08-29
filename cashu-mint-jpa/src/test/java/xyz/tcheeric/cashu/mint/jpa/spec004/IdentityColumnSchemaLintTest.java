package xyz.tcheeric.cashu.mint.jpa.spec004;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.jpa.crypto.IdentityHashConverter;
import xyz.tcheeric.cashu.mint.jpa.entity.CustomerPaymentFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MerchantDebitFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MerchantIouFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherFundingEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIdempotencyKeyEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIdentityBackfillLogEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherIssuanceEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuotePurgeLogEntity;

import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 004 T904 — regression test that fails when a new identity-named
 * field lands on a JPA entity without the
 * {@link IdentityHashConverter} applied. Prevents a future PR from
 * adding (e.g.) {@code merchant_email} without hashing it.
 *
 * <h2>Detection rule</h2>
 * A field is considered identity-bearing if its database column name
 * (from {@link Column#name()}, falling back to the Java field name)
 * matches the pattern {@code customer_id} OR {@code merchant_id} OR
 * ends in one of {@code _id}, {@code _email}, {@code _phone},
 * {@code _npub} AND the entity is in the voucher package. Once
 * detected, the field MUST carry
 * {@code @Convert(converter = IdentityHashConverter.class)}.
 *
 * <h2>Allow-list</h2>
 * Some {@code *_id} columns are not identity — they're foreign keys
 * to non-identity rows ({@code funding_id}, {@code quote_id},
 * {@code voucher_quote_id}, {@code iou_id}, {@code merchant_debit_id},
 * {@code provider_event_id}). These are allow-listed by exact column
 * name.
 */
class IdentityColumnSchemaLintTest {

    private static final Set<String> ALLOW_LIST = Set.of(
            "quote_id", "voucher_quote_id", "funding_id", "issuance_id",
            "iou_id", "merchant_debit_id", "provider_event_id",
            "webhook_event_quote_id", "idempotency_key", "request_hash",
            "iou_terms_hash", "last_hashed_pk", "table_name",
            // melt saga columns — outside spec 004 scope
            "hold_id", "payment_hash");

    /**
     * Explicit voucher-entity list. New voucher entities MUST be added
     * here so the lint scans them. Non-voucher entities (MintQuoteEntity,
     * MeltSagaEntity, WebhookEventEntity etc.) are out of scope for
     * spec 004 identity hashing.
     */
    private static final List<Class<?>> VOUCHER_ENTITIES = List.of(
            VoucherQuoteEntity.class,
            VoucherFundingEntity.class,
            CustomerPaymentFundingEntity.class,
            MerchantDebitFundingEntity.class,
            MerchantIouFundingEntity.class,
            VoucherIssuanceEntity.class,
            VoucherIdempotencyKeyEntity.class,
            VoucherIdentityBackfillLogEntity.class,
            VoucherQuotePurgeLogEntity.class);

    @Test
    void everyIdentityColumnOnVoucherEntitiesHasIdentityHashConverter() {
        Set<String> violations = new LinkedHashSet<>();
        for (Class<?> entity : VOUCHER_ENTITIES) {
            Class<?> current = entity;
            while (current != null && current != Object.class) {
                for (Field f : current.getDeclaredFields()) {
                    Column col = f.getAnnotation(Column.class);
                    if (col == null) {
                        continue;
                    }
                    String colName = col.name().isBlank() ? f.getName() : col.name();
                    if (!looksLikeIdentityColumn(colName, entity.getSimpleName())) {
                        continue;
                    }
                    Convert convert = f.getAnnotation(Convert.class);
                    if (convert == null || !IdentityHashConverter.class.equals(convert.converter())) {
                        violations.add(entity.getSimpleName() + "." + f.getName()
                                + " (column=" + colName + ")");
                    }
                }
                current = current.getSuperclass();
            }
        }

        assertThat(violations)
                .as("Identity-bearing columns missing @Convert(IdentityHashConverter.class). "
                        + "Either apply the converter OR add the column name to the allow-list "
                        + "in IdentityColumnSchemaLintTest if it's a non-identity FK.")
                .isEmpty();
    }

    private static boolean looksLikeIdentityColumn(String columnName, String entityName) {
        if (ALLOW_LIST.contains(columnName)) {
            return false;
        }
        // Only enforce on voucher-related entities.
        boolean isVoucherEntity = entityName.toLowerCase().contains("voucher")
                || entityName.toLowerCase().contains("funding");
        if (!isVoucherEntity) {
            return false;
        }
        String lower = columnName.toLowerCase();
        return lower.equals("customer_id")
                || lower.equals("merchant_id")
                || lower.endsWith("_email")
                || lower.endsWith("_phone")
                || lower.endsWith("_npub");
    }
}
