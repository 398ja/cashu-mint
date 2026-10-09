package xyz.tcheeric.cashu.mint.proto.voucher;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherRedemptionCapMode;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherRedemptionLedger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Caps what can ever be credited as one coupon at the sats issued for it (cashu-mint#540).
 *
 * <h2>The hole</h2>
 *
 * <p>Every proof of an unlocked coupon carries the same signed blob, and only the nonce differs.
 * Swap outputs are blinded, so anyone who has seen the blob can swap plain sats into fresh proofs
 * carrying it and spend them as the coupon. Each one passes every check the mint has: the
 * signature is genuine, the signer is trusted, and the fresh nonce gives a fresh {@code Y}.
 *
 * <h2>Why the redeemer declares</h2>
 *
 * <p>The mint cannot attribute a swap's outputs to a voucher: they are blinded. A rule that
 * counted a voucher swap's outputs as "minted as V" would also count the attacker's own spend, so
 * it would never move. What the mint can be told is the moment value leaves the voucher system:
 * a redeemer crediting someone for V. The redeemer, not the holder, sends that declaration, so
 * the holder cannot leave it out. Honest hops between wallets are not declared and are never
 * counted, so a long split chain is untouched. The declared credits are what must add up to at
 * most one coupon, and that is all this checks.
 *
 * <h2>Modes</h2>
 *
 * {@code off} ignores declarations; {@code log} (default) records them, accepts one over the cap
 * and reports it; {@code enforce} refuses a swap whose declaration would go over the cap, before
 * anything is signed.
 */
@Slf4j
public final class VoucherRedemptionCap {

    private final VoucherRedemptionCapMode mode;
    private final VoucherRedemptionLedger ledger;
    private final Set<byte[]> redeemerTokenDigests;

    /**
     * @param mode           the configured mode
     * @param ledger         where the per-voucher totals live, or null when the mint has no
     *                       durable store (then every declaration is ignored, with a warning)
     * @param redeemerTokens the tokens a redeemer must present to declare
     * @throws IllegalStateException in {@code enforce} with no ledger or no redeemer token, which
     *                               would either refuse nothing or refuse every declaration
     */
    public VoucherRedemptionCap(@NonNull VoucherRedemptionCapMode mode,
                                VoucherRedemptionLedger ledger,
                                @NonNull List<String> redeemerTokens) {
        Set<byte[]> digests = new HashSet<>();
        for (String token : redeemerTokens) {
            if (token != null && !token.isBlank()) {
                digests.add(sha256(token.strip()));
            }
        }
        if (mode == VoucherRedemptionCapMode.ENFORCE && (ledger == null || digests.isEmpty())) {
            throw new IllegalStateException("cashu.mint.voucher.redemption-cap=enforce needs the durable "
                    + "store (cashu.mint.jpa.enabled=true) and at least one "
                    + "cashu.mint.voucher.redeemer-tokens entry. Use redemption-cap=log until both are set.");
        }
        this.mode = mode;
        this.ledger = ledger;
        this.redeemerTokenDigests = digests;
    }

    /** What a mint gets when nothing was configured: {@code log}, no ledger, no tokens. */
    public static VoucherRedemptionCap unconfigured() {
        return new VoucherRedemptionCap(VoucherRedemptionCapMode.LOG, null, List.of());
    }

    /** @return the configured mode */
    public VoucherRedemptionCapMode mode() {
        return mode;
    }

    /**
     * Checks the declaration's authority and shape against the swap's inputs, before anything else
     * about the swap is decided. Cheap and side-effect free.
     *
     * @param declaration   the declaration, or null when the swap carries none
     * @param redeemerToken the presented redeemer token, or null
     * @param inputs        the swap's inputs
     * @return the voucher terms the declaration will be claimed against, or empty when there is
     *         nothing to claim
     * @throws CashuErrorException {@code clear_auth_failed} for a declaration without a valid
     *                             redeemer token, {@code voucher_not_accepted} for one that does not
     *                             match its inputs (both in every mode but {@code off})
     */
    public <T extends Secret> Optional<VoucherTerms> admit(VoucherRedemptionDeclaration declaration,
                                                           String redeemerToken,
                                                           @NonNull List<Proof<T>> inputs)
            throws CashuErrorException {
        if (declaration == null || mode == VoucherRedemptionCapMode.OFF) {
            return Optional.empty();
        }
        if (!isTrustedRedeemer(redeemerToken)) {
            // A declaration spends someone's coupon allowance, so anyone able to send one could
            // exhaust a coupon they do not hold. Refused in log mode too: the header is new, and a
            // caller sending it without a valid token is misconfigured, not legacy.
            log.warn("voucher_redemption_unauthorised voucherId={} mode={}",
                    declaration.voucherIdPrefix(), mode.label());
            throw new CashuErrorException(CashuErrorCode.clear_auth_failed,
                    "Voucher redemption declarations need a trusted redeemer token");
        }
        VoucherTerms terms = null;
        long voucherSats = 0;
        for (Proof<T> input : inputs) {
            Optional<VoucherTerms> inputTerms = VoucherTerms.of(input.getSecret());
            if (inputTerms.isEmpty()) {
                throw mismatch(declaration, "an input is not a readable voucher");
            }
            if (!inputTerms.get().voucherId().equalsIgnoreCase(declaration.voucherId())) {
                throw mismatch(declaration, "inputs name another voucher");
            }
            if (terms == null || (terms.capSats() == null && inputTerms.get().capSats() != null)) {
                terms = inputTerms.get();
            }
            voucherSats += input.getAmount();
        }
        if (terms == null) {
            throw mismatch(declaration, "no inputs");
        }
        if (declaration.sats() > voucherSats) {
            throw mismatch(declaration, "declares more sats than its inputs carry");
        }
        return Optional.of(terms);
    }

    /**
     * Claims the declared sats against the voucher's cap. Call after the swap has validated and
     * before it signs, under the inputs' lock. {@link #release} it if the swap then fails with
     * nothing signed.
     *
     * @param declaration the admitted declaration
     * @param terms       what {@link #admit} returned
     * @return whether a claim was recorded, and so must be released if the swap fails unsigned
     * @throws CashuErrorException {@code voucher_not_accepted} in {@code enforce} when the claim
     *                             would exceed the cap
     */
    public boolean claim(@NonNull VoucherRedemptionDeclaration declaration, @NonNull VoucherTerms terms)
            throws CashuErrorException {
        if (mode == VoucherRedemptionCapMode.OFF) {
            return false;
        }
        if (ledger == null) {
            log.warn("voucher_redemption_unrecorded voucherId={} reason=no_durable_store",
                    declaration.voucherIdPrefix());
            return false;
        }
        boolean enforce = mode == VoucherRedemptionCapMode.ENFORCE;
        VoucherRedemptionLedger.Claim claim =
                ledger.claim(terms.voucherId(), terms.capSats(), declaration.sats(), enforce);
        if (claim.capSats() == null) {
            MetricRecorders.voucher().redemptionDeclared("cap_unknown");
            log.info("voucher_redemption_cap_unknown voucherId={} sats={} redeemed={}",
                    declaration.voucherIdPrefix(), declaration.sats(), claim.redeemedSats());
            return claim.accepted();
        }
        if (claim.overCap(declaration.sats())) {
            MetricRecorders.voucher().overspend(mode);
            log.warn("[alert] voucher_overspend mode={} voucherId={} sats={} redeemed={} cap={} outcome={}",
                    mode.label(), declaration.voucherIdPrefix(), declaration.sats(), claim.redeemedSats(),
                    claim.capSats(), claim.accepted() ? "accepted" : "refused");
        }
        if (!claim.accepted()) {
            MetricRecorders.voucher().redemptionDeclared("refused");
            throw new CashuErrorException(CashuErrorCode.voucher_not_accepted,
                    "Voucher redemption exceeds the sats issued for this voucher");
        }
        MetricRecorders.voucher().redemptionDeclared("accepted");
        log.info("voucher_redemption_claimed voucherId={} sats={} redeemed={} cap={}",
                declaration.voucherIdPrefix(), declaration.sats(), claim.redeemedSats(), claim.capSats());
        return true;
    }

    /**
     * Gives back a claim whose swap failed before anything was signed.
     *
     * @param declaration the claimed declaration
     * @param terms       its terms
     */
    public void release(@NonNull VoucherRedemptionDeclaration declaration, @NonNull VoucherTerms terms) {
        if (ledger == null) {
            return;
        }
        try {
            ledger.release(terms.voucherId(), declaration.sats());
            log.info("voucher_redemption_released voucherId={} sats={}",
                    declaration.voucherIdPrefix(), declaration.sats());
        } catch (RuntimeException releaseFailure) {
            // Fail-safe direction: a claim that is not given back only makes the coupon look more
            // spent, never less. Logged for an operator.
            log.error("[alert] voucher_redemption_release_failed voucherId={} sats={}",
                    declaration.voucherIdPrefix(), declaration.sats(), releaseFailure);
        }
    }

    private boolean isTrustedRedeemer(String token) {
        if (token == null || token.isBlank() || redeemerTokenDigests.isEmpty()) {
            return false;
        }
        byte[] presented = sha256(token.strip());
        boolean match = false;
        for (byte[] digest : redeemerTokenDigests) {
            match |= MessageDigest.isEqual(digest, presented);
        }
        return match;
    }

    private CashuErrorException mismatch(VoucherRedemptionDeclaration declaration, String why) {
        log.warn("voucher_redemption_mismatch voucherId={} reason=\"{}\"", declaration.voucherIdPrefix(), why);
        return new CashuErrorException(CashuErrorCode.voucher_not_accepted,
                "Voucher redemption declaration does not match the swap: " + why);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException everyJvmHasIt) {
            throw new IllegalStateException(everyJvmHasIt);
        }
    }
}
