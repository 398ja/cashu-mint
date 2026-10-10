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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

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
 * <p>The mint cannot attribute a swap's outputs to a voucher: they are blinded. What it can be told
 * is the moment value leaves the voucher system: a redeemer crediting someone for V. The redeemer,
 * not the holder, sends that declaration, so the holder cannot leave it out. Honest hops between
 * wallets are not declared and are never counted. The declared credits are what must add up to at
 * most one coupon, and that is all this checks.
 *
 * <h2>Undoing a credit</h2>
 *
 * <p>A declaration consumes cap. If the credit it stood for is undone (refund, reversal, a
 * downstream failure after the swap), the redeemer must give it back with {@link #release}, or an
 * honest redeemer becomes a free griefing oracle: relabel plain sats onto a victim's coupon, have
 * it credited, get refunded, and the victim's coupon is spent for nothing. A release names the
 * declared swap by the receipt it answered with (its NUT-19 request fingerprint), is honoured only
 * for the redeemer that declared it, gives back exactly what that swap declared, and only once.
 *
 * <h2>Modes</h2>
 *
 * <ul>
 *   <li>{@code off}: the headers are not read at all.</li>
 *   <li>{@code log} (default): every trusted declaration is recorded, and one over the cap is
 *       accepted and reported. A declaration that is malformed, untrusted or does not match its
 *       inputs is logged, counted ({@code redemption_declared_total{outcome="ignored"}}) and
 *       ignored. Log mode never refuses a swap: a redeemer that ships the header before the mint
 *       has its token must not cause an outage.</li>
 *   <li>{@code enforce}: any of those refuses the swap before anything is signed.</li>
 * </ul>
 *
 * <h2>The redeemer token</h2>
 *
 * <p>Compared as a SHA-256 digest in constant time. Never logged, never stored: the claim records
 * the digest, and logs show its first 8 hex characters so a declaration can be attributed to the
 * service that made it (one token per service).
 */
@Slf4j
public final class VoucherRedemptionCap {

    private final VoucherRedemptionCapMode mode;
    private final VoucherRedemptionLedger ledger;
    private final List<byte[]> redeemerTokenDigests;

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
        List<byte[]> digests = new ArrayList<>();
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
        this.redeemerTokenDigests = List.copyOf(digests);
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
     * Reads a swap's declaration headers and checks the redeemer's authority. Cheap and
     * side-effect free apart from logging and counting.
     *
     * @param rawDeclaration the {@value VoucherRedemptionDeclaration#HEADER} header, may be null
     * @param redeemerToken  the {@value VoucherRedemptionDeclaration#REDEEMER_HEADER} header, may be null
     * @return the authorised declaration, or empty when there is none, when the mode is
     *         {@code off}, or (in {@code log}) when it is malformed or untrusted
     * @throws CashuErrorException in {@code enforce} only: {@code voucher_not_accepted} for a
     *                             malformed declaration, {@code clear_auth_failed} for one without
     *                             a trusted redeemer token
     */
    public Optional<Authorised> authorise(String rawDeclaration, String redeemerToken) throws CashuErrorException {
        if (mode == VoucherRedemptionCapMode.OFF || rawDeclaration == null || rawDeclaration.isBlank()) {
            return Optional.empty();
        }
        VoucherRedemptionDeclaration declaration;
        try {
            declaration = VoucherRedemptionDeclaration.parse(rawDeclaration).orElse(null);
        } catch (IllegalArgumentException malformed) {
            return refuseOrIgnore("malformed", "?", CashuErrorCode.voucher_not_accepted, malformed.getMessage());
        }
        if (declaration == null) {
            return Optional.empty();
        }
        Optional<String> digest = trustedDigest(redeemerToken);
        if (digest.isEmpty()) {
            // A declaration spends someone's coupon allowance, so anyone able to send one could
            // exhaust a coupon they do not hold.
            return refuseOrIgnore("unauthorised", declaration.voucherIdPrefix(), CashuErrorCode.clear_auth_failed,
                    "Voucher redemption declarations need a trusted redeemer token");
        }
        return Optional.of(new Authorised(declaration, digest.get()));
    }

    /**
     * Checks an authorised declaration against the swap's inputs.
     *
     * @return the voucher terms to claim against, or empty (in {@code log}) when they do not match
     * @throws CashuErrorException in {@code enforce} only, {@code voucher_not_accepted}
     */
    public <T extends Secret> Optional<VoucherTerms> match(@NonNull Authorised authorised,
                                                           @NonNull List<Proof<T>> inputs)
            throws CashuErrorException {
        VoucherRedemptionDeclaration declaration = authorised.declaration();
        VoucherTerms terms = null;
        long voucherSats = 0;
        for (Proof<T> input : inputs) {
            Optional<VoucherTerms> inputTerms = VoucherTerms.of(input.getSecret());
            if (inputTerms.isEmpty()) {
                return mismatch(declaration, "an input is not a readable voucher");
            }
            if (!inputTerms.get().voucherId().equalsIgnoreCase(declaration.voucherId())) {
                return mismatch(declaration, "inputs name another voucher");
            }
            Long cap = inputTerms.get().capSats();
            if (terms != null && terms.capSats() != null && cap != null && !terms.capSats().equals(cap)) {
                return mismatch(declaration, "inputs state different terms for one voucher");
            }
            if (terms == null || (terms.capSats() == null && cap != null)) {
                terms = inputTerms.get();
            }
            voucherSats += input.getAmount();
        }
        if (terms == null) {
            return mismatch(declaration, "no inputs");
        }
        if (declaration.sats() > voucherSats) {
            return mismatch(declaration, "declares more sats than its inputs carry");
        }
        return Optional.of(terms);
    }

    /**
     * Claims the declared sats against the voucher's cap, under the swap's request fingerprint and
     * hold. Call once the inputs are held and before signing. {@link #giveBack} the request if the
     * swap then fails with nothing signed, whether or not this returned.
     *
     * @return whether this request now holds a standing claim
     * @throws CashuErrorException {@code voucher_not_accepted} in {@code enforce} when the claim
     *                             would exceed the cap or contradicts the cap on record
     */
    public boolean claim(@NonNull Authorised authorised, @NonNull VoucherTerms terms,
                         @NonNull String requestFingerprint, @NonNull String holdId)
            throws CashuErrorException {
        VoucherRedemptionDeclaration declaration = authorised.declaration();
        if (mode == VoucherRedemptionCapMode.OFF) {
            return false;
        }
        if (ledger == null) {
            log.warn("voucher_redemption_unrecorded voucherId={} reason=no_durable_store",
                    declaration.voucherIdPrefix());
            return false;
        }
        boolean enforce = mode == VoucherRedemptionCapMode.ENFORCE;
        VoucherRedemptionLedger.Claim claim;
        try {
            claim = ledger.claim(new VoucherRedemptionLedger.ClaimRequest(
                    terms.voucherId(), terms.capSats(), declaration.sats(), enforce, requestFingerprint,
                    authorised.redeemerDigest(), holdId));
        } catch (RuntimeException ledgerFailure) {
            // Re-review: log never refuses a swap, and that includes the ledger being unreachable
            // (database outage, lock timeout, a node whose migration has not run). In enforce the
            // failure refuses, as an unknown claim must: the caller gives back whatever may have
            // been recorded and releases the hold.
            if (enforce) {
                throw ledgerFailure;
            }
            MetricRecorders.voucher().redemptionDeclared("ignored");
            log.error("[alert] voucher_redemption_ledger_failed voucherId={} mode={} outcome=ignored request={} "
                            + "cause={}", declaration.voucherIdPrefix(), mode.label(), prefix(requestFingerprint),
                    ledgerFailure.getClass().getSimpleName());
            return false;
        }
        String redeemer = authorised.redeemerLabel();

        switch (claim.outcome()) {
            case REUSED -> {
                MetricRecorders.voucher().redemptionDeclared("reused");
                log.info("voucher_redemption_reused voucherId={} sats={} redeemed={} cap={} redeemer={} request={}",
                        declaration.voucherIdPrefix(), declaration.sats(), claim.redeemedSats(), claim.capSats(),
                        redeemer, prefix(requestFingerprint));
                return true;
            }
            case DECLARATION_MISMATCH -> {
                mismatch(declaration, "a retry of this request declared something else the first time");
                return false;
            }
            case REFUSED_CAP_MISMATCH -> {
                MetricRecorders.voucher().redemptionDeclared("refused");
                throw new CashuErrorException(CashuErrorCode.voucher_not_accepted,
                        "Voucher redemption refused: the voucher's terms do not match those on record");
            }
            default -> {
                // ACCEPTED or REFUSED_OVER_CAP, below
            }
        }
        if (claim.capSats() == null) {
            MetricRecorders.voucher().redemptionDeclared("cap_unknown");
            log.info("voucher_redemption_cap_unknown voucherId={} sats={} redeemed={} redeemer={}",
                    declaration.voucherIdPrefix(), declaration.sats(), claim.redeemedSats(), redeemer);
            return claim.accepted();
        }
        if (claim.overCap(declaration.sats())) {
            MetricRecorders.voucher().overspend(mode);
            log.warn("[alert] voucher_overspend mode={} voucherId={} sats={} redeemed={} cap={} outcome={} "
                            + "redeemer={} request={}",
                    mode.label(), declaration.voucherIdPrefix(), declaration.sats(), claim.redeemedSats(),
                    claim.capSats(), claim.accepted() ? "accepted" : "refused", redeemer,
                    prefix(requestFingerprint));
        }
        if (!claim.accepted()) {
            MetricRecorders.voucher().redemptionDeclared("refused");
            throw new CashuErrorException(CashuErrorCode.voucher_not_accepted,
                    "Voucher redemption exceeds the sats issued for this voucher");
        }
        MetricRecorders.voucher().redemptionDeclared("accepted");
        log.info("voucher_redemption_claimed voucherId={} sats={} redeemed={} cap={} redeemer={} request={}",
                declaration.voucherIdPrefix(), declaration.sats(), claim.redeemedSats(), claim.capSats(),
                redeemer, prefix(requestFingerprint));
        return true;
    }

    /**
     * Gives back the claim of a request whose swap failed before anything was signed. Safe to call
     * whether or not a claim was made, or reached the database.
     *
     * @param requestFingerprint the swap's NUT-19 request fingerprint
     */
    public void giveBack(@NonNull String requestFingerprint) {
        if (ledger == null || mode == VoucherRedemptionCapMode.OFF) {
            return;
        }
        try {
            if (ledger.giveBack(requestFingerprint)) {
                log.info("voucher_redemption_given_back request={}", prefix(requestFingerprint));
            }
        } catch (RuntimeException giveBackFailure) {
            // Fail-safe direction: a claim not given back only makes the coupon look more spent.
            // A NUT-19 retry of the same request reuses it, and the hold sweeper gives it back if
            // no retry comes.
            log.error("[alert] voucher_redemption_give_back_failed request={}", prefix(requestFingerprint),
                    giveBackFailure);
        }
    }

    /**
     * A redeemer gives back the declaration of one of its own swaps, because the credit it stood
     * for was undone.
     *
     * @param redeemerToken      the presenting redeemer's token
     * @param voucherId          the voucher the swap declared
     * @param requestFingerprint the receipt the declared swap answered with
     * @param sats               the sats to release, or null for the whole declaration
     * @return what happened
     * @throws CashuErrorException {@code clear_auth_failed} for an untrusted token
     */
    public VoucherRedemptionLedger.Release release(String redeemerToken, @NonNull String voucherId,
                                                   @NonNull String requestFingerprint, Long sats)
            throws CashuErrorException {
        Optional<String> digest = trustedDigest(redeemerToken);
        if (digest.isEmpty()) {
            log.warn("voucher_redemption_release_unauthorised voucherId={}", prefix(voucherId));
            throw new CashuErrorException(CashuErrorCode.clear_auth_failed,
                    "Voucher redemption releases need a trusted redeemer token");
        }
        if (ledger == null || mode == VoucherRedemptionCapMode.OFF) {
            return new VoucherRedemptionLedger.Release(VoucherRedemptionLedger.ReleaseOutcome.NOT_FOUND, 0, 0);
        }
        VoucherRedemptionLedger.Release release = ledger.release(requestFingerprint, voucherId, digest.get(), sats);
        if (release.outcome() == VoucherRedemptionLedger.ReleaseOutcome.RELEASED) {
            MetricRecorders.voucher().redemptionDeclared("released");
        }
        log.info("voucher_redemption_release voucherId={} request={} outcome={} released={} declared={} redeemer={}",
                prefix(voucherId), prefix(requestFingerprint), release.outcome(), release.releasedSats(),
                release.declaredSats(), digest.get().substring(0, 8));
        return release;
    }

    private Optional<Authorised> refuseOrIgnore(String reason, String voucherIdPrefix, CashuErrorCode code,
                                                String message) throws CashuErrorException {
        log.warn("voucher_redemption_{} voucherId={} mode={} outcome={}", reason, voucherIdPrefix, mode.label(),
                mode == VoucherRedemptionCapMode.ENFORCE ? "refused" : "ignored");
        if (mode == VoucherRedemptionCapMode.ENFORCE) {
            throw new CashuErrorException(code, message);
        }
        MetricRecorders.voucher().redemptionDeclared("ignored");
        return Optional.empty();
    }

    private Optional<VoucherTerms> mismatch(VoucherRedemptionDeclaration declaration, String why)
            throws CashuErrorException {
        log.warn("voucher_redemption_mismatch voucherId={} mode={} reason=\"{}\" outcome={}",
                declaration.voucherIdPrefix(), mode.label(), why,
                mode == VoucherRedemptionCapMode.ENFORCE ? "refused" : "ignored");
        if (mode == VoucherRedemptionCapMode.ENFORCE) {
            throw new CashuErrorException(CashuErrorCode.voucher_not_accepted,
                    "Voucher redemption declaration does not match the swap: " + why);
        }
        MetricRecorders.voucher().redemptionDeclared("ignored");
        return Optional.empty();
    }

    /** The hex digest of the presented token, if it is one the mint trusts. Constant time. */
    private Optional<String> trustedDigest(String token) {
        if (token == null || token.isBlank() || redeemerTokenDigests.isEmpty()) {
            return Optional.empty();
        }
        byte[] presented = sha256(token.strip());
        boolean match = false;
        for (byte[] digest : redeemerTokenDigests) {
            match |= MessageDigest.isEqual(digest, presented);
        }
        return match ? Optional.of(HexFormat.of().formatHex(presented)) : Optional.empty();
    }

    private static String prefix(String value) {
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException everyJvmHasIt) {
            throw new IllegalStateException(everyJvmHasIt);
        }
    }

    /**
     * A declaration from a trusted redeemer.
     *
     * @param declaration    what it declares
     * @param redeemerDigest the hex SHA-256 of the token that declared it, never the token
     */
    public record Authorised(@NonNull VoucherRedemptionDeclaration declaration, @NonNull String redeemerDigest) {

        /** @return the digest's first 8 hex characters: which service declared, for logs */
        public String redeemerLabel() {
            return redeemerDigest.substring(0, 8);
        }
    }
}
