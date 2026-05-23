package xyz.tcheeric.cashu.mint.proto.ports;

import java.time.Instant;

/**
 * Append-only ledger row witnessing a successful mint issuance. Keyed by
 * {@code quote_id}. Stores the outputs fingerprint + signed promises so a NUT-19
 * retry can return identical signatures without re-signing.
 *
 * <p>Spec 001: data-model § IssuanceRecord.
 */
public interface IssuanceRecord {

    String quoteId();

    /** SHA-256 over the sorted blinded-message tuples per research R4. */
    String outputsHash();

    /** Serialised {@code List<BlindSignature>} returned verbatim on retry. */
    String signaturesJson();

    String keysetId();

    long totalAmount();

    Instant issuedAt();
}
