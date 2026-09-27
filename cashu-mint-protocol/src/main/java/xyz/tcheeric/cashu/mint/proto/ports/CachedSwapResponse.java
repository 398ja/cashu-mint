package xyz.tcheeric.cashu.mint.proto.ports;

import java.time.Instant;

/**
 * A successful {@code POST /v1/swap} response, kept so a replay of the same request gets it back
 * (NUT-19, issue #482).
 *
 * <p>Without it a wallet that loses its connection mid-swap cannot tell "the mint burned my inputs
 * and signed outputs I never received" from "the mint never saw the request". Replaying used to be
 * refused with {@code outputs_already_signed}, stranding the value. With it, the replay returns
 * the same signatures and the wallet carries on.
 */
public interface CachedSwapResponse {

    /** Hex SHA-256 over the request's inputs and outputs, see {@code SwapRequestFingerprint}. */
    String requestFingerprint();

    /**
     * The serialised {@code PostSwapResponse}. A replay returns the same signatures, though not
     * necessarily the same bytes: the column is JSONB, which normalises key order and whitespace.
     */
    String responseJson();

    /** The moment this response stops being replayable, set from the advertised NUT-19 ttl. */
    Instant expiresAt();
}
