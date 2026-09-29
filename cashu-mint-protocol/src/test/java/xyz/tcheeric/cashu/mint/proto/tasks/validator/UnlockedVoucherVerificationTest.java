package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.voucher.domain.UnlockedVoucherBlob;
import xyz.tcheeric.cashu.voucher.domain.VoucherMetadata;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the mint can now see in an unlocked voucher, and could not before.
 *
 * <h2>The bug</h2>
 *
 * <p>An unlocked voucher reaches the mint as {@code ["VOUCHER", <blob>, nonce, []]}: its terms
 * live in CBOR inside {@code data} and the tag array is EMPTY. Every check in
 * {@code VoucherSpendingCondition} read tags, so for these vouchers they all found nothing and
 * passed. No signature check, no expiry check, no issuer binding. Anyone who had seen a blob
 * could swap sats into outputs carrying it and be honoured.
 *
 * <h2>What this covers, and what it does not</h2>
 *
 * <p>These tests assert the READABILITY that the fix depends on, against a secret captured
 * from a real token. That is the part that was wrong and the part a synthetic fixture would
 * get wrong in the same way: my first attempt at the decoder hex-decoded {@code data} twice
 * and returned null on every real voucher, which a hand-built fixture would have agreed with.
 *
 * <p>The condition's own refusals need a mint, a vault and a keyset, so they are exercised by
 * the integration suite rather than here. What is pinned here is that the mint can now obtain
 * the signature, the key and the expiry it refuses on, and that verification over them
 * succeeds for a genuine voucher.
 *
 * <p>cashu-mint#525.
 */
class UnlockedVoucherVerificationTest {

    private static WellKnownSecret capturedUnlockedVoucher() {
        try (InputStream in = UnlockedVoucherVerificationTest.class
                .getResourceAsStream("/captured-unlocked-voucher-secret.json")) {
            assertNotNull(in, "the captured unlocked voucher fixture must be on the classpath");
            return (WellKnownSecret) SecretUtil.toSecret(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
        } catch (IOException cannotRead) {
            throw new IllegalStateException(cannotRead);
        }
    }

    /**
     * The shape of the bug, recorded. Every guard the mint had was built on tags, and a real
     * unlocked voucher has none, so every guard passed.
     */
    @Test
    @DisplayName("a real unlocked voucher exposes nothing through its tags")
    void tagsExposeNothing() {
        WellKnownSecret secret = capturedUnlockedVoucher();

        assertTrue(secret.getTags() == null || secret.getTags().isEmpty(),
                "an empty tag array is why every tag-based check passed");
        assertNull(VoucherMetadata.issuerSignature(secret));
        assertNull(VoucherMetadata.issuerPublicKey(secret));
        assertFalse(VoucherMetadata.isSigned(secret),
                "so the old isSigned short-circuit skipped the signature check entirely");
    }

    /** And now the mint can read the same fields a locked voucher carries as tags. */
    @Test
    @DisplayName("the mint can now read the signature, key and expiry from the blob")
    void blobExposesTheTerms() {
        VoucherSecret voucher = UnlockedVoucherBlob.read(capturedUnlockedVoucher());

        assertNotNull(voucher, "a real captured voucher must be readable");
        assertNotNull(voucher.getIssuerSignature(), "the signature it refuses on");
        assertNotNull(voucher.getIssuerPublicKey(), "the key it verifies against");
        assertNotNull(voucher.getIssuerId(), "the issuer it binds to");
        assertTrue(VoucherMetadata.isSigned(voucher),
                "so a signed voucher is now distinguishable from an unsigned one");
    }

    /**
     * The claim that matters. A decode producing plausible fields in the wrong shape would
     * satisfy every assertion above and fail every signature, which would refuse every
     * genuine voucher rather than accepting forged ones.
     */
    @Test
    @DisplayName("a genuine unlocked voucher verifies once its terms are readable")
    void genuineVoucherVerifies() {
        VoucherSecret voucher = UnlockedVoucherBlob.read(capturedUnlockedVoucher());

        assertNotNull(voucher);
        assertTrue(VoucherSignatureService.verify(voucher),
                "the fix must accept real vouchers, not merely refuse fake ones");
    }

    /**
     * A blob that will not read leaves the mint unable to check anything, so the condition
     * refuses rather than accepting it unverified. Pinned here as the reader's answer, since
     * that is what the refusal keys on.
     */
    @Test
    @DisplayName("an unreadable blob yields no terms, which is what the mint refuses on")
    void unreadableBlobYieldsNothing() {
        WellKnownSecret rubbish = (WellKnownSecret) SecretUtil.toSecret(
                "[\"VOUCHER\",{\"nonce\":\"00\",\"data\":\"deadbeef\",\"tags\":[]}]");

        assertNull(UnlockedVoucherBlob.read(rubbish),
                "and an unchecked voucher must not be spendable");
    }
}
