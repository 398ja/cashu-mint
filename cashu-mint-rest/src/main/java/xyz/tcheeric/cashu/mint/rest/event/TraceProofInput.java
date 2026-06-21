package xyz.tcheeric.cashu.mint.rest.event;

/**
 * Spec 036 — lightweight, immutable carrier for a melt input proof on a
 * {@code MELT_FAILED} trace event. Holds only the public proof identity
 * ({@code y}), amount and keyset — never the plaintext secret (Principle VII).
 * The controller computes {@code y} via {@code SecretUtil.toY(secret)} so the
 * event and factory stay free of protocol {@code Proof} types.
 */
public record TraceProofInput(long amount, String keysetId, String y) {
}
