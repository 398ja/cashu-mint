package xyz.tcheeric.cashu.mint.proto.ports;

import java.util.Optional;

/**
 * Port for the append-only {@code issuance_record} table.
 *
 * <p>Spec 001: data-model § IssuanceRecord. Used by {@code MintTask} for both
 * the happy path (insert on successful issuance) and the NUT-19 idempotent
 * replay path (lookup by {@code quote_id} returns the previously signed
 * promises).
 */
public interface IssuanceRecordRepository {

    /** Loads the issuance record for a given quote, if any. */
    Optional<IssuanceRecord> findById(String quoteId);

    /**
     * Inserts an issuance record if none exists for the quote, otherwise returns
     * the existing row. Implementations MUST treat a PK conflict as
     * "already issued" rather than throwing.
     *
     * @return {@link InsertResult#newlyInserted} when this caller's row was the one
     *         persisted, {@link InsertResult#existing} when another concurrent
     *         writer already wrote a row for the same {@code quote_id}.
     */
    InsertResult insertIfAbsent(IssuanceRecord record);

    sealed interface InsertResult permits InsertResult.NewlyInserted, InsertResult.Existing {

        IssuanceRecord record();

        record NewlyInserted(IssuanceRecord record) implements InsertResult {}

        record Existing(IssuanceRecord record) implements InsertResult {}

        static InsertResult newlyInserted(IssuanceRecord record) {
            return new NewlyInserted(record);
        }

        static InsertResult existing(IssuanceRecord record) {
            return new Existing(record);
        }
    }
}
