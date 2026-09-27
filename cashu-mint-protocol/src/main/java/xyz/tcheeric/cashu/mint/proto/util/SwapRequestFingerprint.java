package xyz.tcheeric.cashu.mint.proto.util;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * The NUT-19 cache key for a {@code POST /v1/swap} request (issue #482).
 *
 * <p>A SHA-256 digest over both halves of the swap. Covering the outputs alone, as
 * {@link OutputsHash} does for the mint path, is not enough here: the mint path is keyed per
 * quote, but a swap has no quote, so two different swaps asking for identical outputs would share
 * a key and the second would be handed the first one's signatures.
 *
 * <p>What goes in, and why:
 * <ul>
 *   <li><b>Inputs</b> as {@code (amount, keyset id, secret, C)}, sorted by secret. Input order
 *       has no bearing on the response, so a wallet that rebuilds its input list in a different
 *       order still replays.</li>
 *   <li><b>Outputs</b> as {@code (amount, keyset id, B_)}, <em>in request order</em>. The response
 *       is positional, since a wallet pairs the n-th signature with its n-th blinded message, so
 *       a reordered replay must miss rather than receive signatures it would unblind against the
 *       wrong factors.</li>
 * </ul>
 *
 * <p>Deliberately left out: the NUT-11 {@code witness} and any input DLEQ. A wallet retrying a
 * P2PK swap re-signs it, and BIP-340 signatures carry fresh randomness, so keying on the witness
 * would turn every locked retry into a miss. Leaving it out gives nothing away: the cached
 * signatures are over the same {@code B_} values, and only the holder of their blinding factors
 * can unblind them.
 *
 * <p>Every field is length-prefixed rather than delimited, because NUT-10 secrets are arbitrary
 * JSON and could otherwise contain whatever separator was chosen.
 *
 * @param hex hex-encoded SHA-256 digest, 64 characters
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/19.md">NUT-19</a>
 */
public record SwapRequestFingerprint(@NonNull String hex) {

    /** Separates this digest from any other SHA-256 the mint computes over similar fields. */
    private static final String DOMAIN = "cashu-mint/v1/swap/request-fingerprint/1";

    private static final Comparator<Proof<?>> BY_SECRET =
            Comparator.comparing(SwapRequestFingerprint::secretOf);

    /**
     * Fingerprints a swap request.
     *
     * <p>A missing input or output list hashes as empty. Such a request is refused by validation
     * and so never stores a response, which means its fingerprint can never find one either.
     *
     * @param request the swap request as received
     * @return the request's cache key
     */
    public static <T extends Secret> SwapRequestFingerprint of(@NonNull PostSwapRequest<T> request) {
        MessageDigest digest = sha256();
        update(digest, DOMAIN);
        digestInputs(digest, nullToEmpty(request.getInputs()));
        digestOutputs(digest, nullToEmpty(request.getBlindedMessages()));
        return new SwapRequestFingerprint(HexFormat.of().formatHex(digest.digest()));
    }

    private static <T extends Secret> void digestInputs(MessageDigest digest, List<Proof<T>> inputs) {
        update(digest, inputs.size());
        inputs.stream().sorted(BY_SECRET).forEach(input -> {
            update(digest, input.getAmount());
            update(digest, String.valueOf(input.getKeySetId()));
            update(digest, secretOf(input));
            update(digest, String.valueOf(input.getUnblindedSignature()));
        });
    }

    private static void digestOutputs(MessageDigest digest, List<BlindedMessage> outputs) {
        update(digest, outputs.size());
        for (BlindedMessage output : outputs) {
            update(digest, output.getAmount());
            update(digest, String.valueOf(output.getKeySetId()));
            update(digest, String.valueOf(output.getBlindedMessage()));
        }
    }

    private static String secretOf(Proof<?> proof) {
        return String.valueOf(proof.getSecret());
    }

    private static void update(MessageDigest digest, String field) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        update(digest, bytes.length);
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static <E> List<E> nullToEmpty(List<E> list) {
        return list == null ? List.of() : list;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Override
    public String toString() {
        return hex;
    }
}
