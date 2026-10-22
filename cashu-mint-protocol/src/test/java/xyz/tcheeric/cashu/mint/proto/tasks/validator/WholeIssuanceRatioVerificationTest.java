package xyz.tcheeric.cashu.mint.proto.tasks.validator;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherTags;
import xyz.tcheeric.cashu.common.nut18.VoucherWellKnownSecret;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.voucher.domain.SignedLockedVoucher;
import xyz.tcheeric.cashu.voucher.domain.UnlockedVoucherBlob;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cashu-voucher#59: a voucher with a whole-number issuance ratio verifies again at the mint.
 *
 * <p>From cashu-voucher 0.15.0 (#48) to 0.17.0 every MINIMAL, FIXED and merchant-config coupon
 * whose ratio is a whole number other than 1 failed {@link VoucherSignatureService#verify} at
 * {@code VoucherSpendingCondition}, in every spelling: the unlocked blob was rebuilt by a builder
 * that wrote {@code "50.0"} (refused as non-canonical), and a locked secret carrying the legacy
 * {@code "50.0"} was refused the same way. cashu-lib 0.30.7 writes {@code "50"}, and cashu-voucher
 * 0.17.1 accepts the one legacy spelling. These pin both, on the exact calls the condition makes,
 * so this suite goes red if the mint is ever built on the old pair again.
 */
class WholeIssuanceRatioVerificationTest {

    private static final byte[] ISSUER_KEY = Hex.decode("00".repeat(31) + "02");
    private static final String LOCK_KEY =
            "02c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5";

    private static String issuerPub() {
        return Hex.toHexString(PrivateKey.derivePublicKey(PrivateKey.fromBytes(ISSUER_KEY)).getBytes())
                .substring(2);
    }

    /** The unlocked wire shape gateway-customer emits: CBOR terms in {@code data}, tags empty. */
    @ParameterizedTest
    @ValueSource(doubles = {2.0, 50.0, 700.0, 1.0E7})
    @DisplayName("an unlocked whole-ratio voucher read from its blob verifies")
    void unlockedWholeRatioVerifies(double ratio) {
        String voucherId = UUID.randomUUID().toString();
        VoucherSecret signed = VoucherSecret.builder()
                .voucherId(UUID.fromString(voucherId)).issuerId("merchant-1").unit("EUR")
                .faceValue(5000L).backingStrategy("MINIMAL").issuanceRatio(ratio).faceDecimals(2)
                .build();
        VoucherSignatureService.createSigned(signed, Hex.toHexString(ISSUER_KEY), issuerPub());

        Map<String, Object> blob = new LinkedHashMap<>();
        blob.put("voucherId", voucherId);
        blob.put("issuerId", "merchant-1");
        blob.put("unit", "EUR");
        blob.put("faceValue", 5000L);
        blob.put("backingStrategy", "MINIMAL");
        blob.put("issuanceRatio", ratio);
        blob.put("faceDecimals", 2);
        blob.put("nonce", signed.getNonce());
        blob.put("issuerSignature", signed.getIssuerSignature());
        blob.put("issuerPublicKey", signed.getIssuerPublicKey());
        WellKnownSecret wire = (WellKnownSecret) SecretUtil.toSecret(new VoucherWellKnownSecret(
                VoucherSerializationUtils.toCbor(blob), UUID.randomUUID().toString()).toString());

        VoucherSecret fromBlob = UnlockedVoucherBlob.read(wire);

        assertNotNull(fromBlob, "the blob must read");
        assertTrue(VoucherSignatureService.verify(fromBlob),
                "a genuine whole-ratio voucher must pass the condition's signature check");
    }

    /** A locked voucher signed as {@code "50"}, presented as {@code "50"} or the legacy {@code "50.0"}. */
    @ParameterizedTest
    @ValueSource(strings = {"50", "50.0"})
    @DisplayName("a locked whole-ratio voucher verifies in the canonical and the legacy spelling")
    void lockedWholeRatioVerifies(String wireRatio) {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(Hex.decode(LOCK_KEY));
        secret.setTag(VoucherTags.VOUCHER_ID, List.of(UUID.randomUUID().toString()));
        secret.setTag(VoucherTags.ISSUER, List.of("merchant-1"));
        secret.setTag(VoucherTags.UNIT, List.of("EUR"));
        secret.setTag(VoucherTags.FACE_VALUE, List.of("5000"));
        secret.setTag(VoucherTags.FACE_DECIMALS, List.of("2"));
        secret.setTag(VoucherTags.BACKING_STRATEGY, List.of("MINIMAL"));
        secret.setTag(VoucherTags.ISSUANCE_RATIO, List.of("50"));
        String signed = SignedLockedVoucher.createSigned(secret, Hex.toHexString(ISSUER_KEY), issuerPub())
                .getSecret().toString();
        String onWire = signed.replace("[\"issuance_ratio\",\"50\"]",
                "[\"issuance_ratio\",\"" + wireRatio + "\"]");
        assertTrue(onWire.contains("\"" + wireRatio + "\"]"), "fixture must carry the spelling under test");

        WellKnownSecret parsed = (WellKnownSecret) SecretUtil.toSecret(onWire);

        assertTrue(VoucherSignatureService.verify(parsed),
                "the issuer signed the canonical bytes, which both spellings share");
    }
}
