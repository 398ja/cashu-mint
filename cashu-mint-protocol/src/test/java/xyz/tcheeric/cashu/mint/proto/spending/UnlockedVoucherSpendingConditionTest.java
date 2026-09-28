package xyz.tcheeric.cashu.mint.proto.spending;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.bouncycastle.util.encoders.Hex;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.nut10.WellKnownSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.VoucherSpendingCondition;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static xyz.tcheeric.cashu.mint.proto.util.SignatureTestData.sampleSignature;

/**
 * What the mint does with an UNLOCKED voucher, driven through the condition itself.
 *
 * <h2>The bug, and why these tests exist separately</h2>
 *
 * <p>An unlocked voucher arrives as {@code ["VOUCHER", <cbor blob>, nonce, []]}: its terms live
 * inside {@code data} and the tag array is EMPTY. Every check in the condition read tags, so
 * for these vouchers they all found nothing and passed. A forged signature was never compared
 * against anything (cashu-mint#525).
 *
 * <p>The companion decoder tests assert only that the blob is READABLE. That is necessary and
 * not sufficient: with the condition's use of the decoded voucher removed, every one of them
 * still passed, because none of them run the condition. So the claim "the mint now refuses a
 * forged unlocked voucher" was untested. These tests make it fail when the fix is removed.
 *
 * <p>Vouchers here are built as real CBOR blobs rather than tag-carrying objects, because the
 * blob is the whole point: a tag-carrying voucher was never the broken case.
 */
class UnlockedVoucherSpendingConditionTest {

    private static final String MINT_ID = "1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae";
    private static final String KEYSET_ID = "00abc123def45678";
    private static final int AMOUNT = 8;
    private static final String NONCE = "00".repeat(16);

    private MintProtocolService mockMintProtocolService;
    private ProofVaultService mockProofVaultService;
    private VoucherSpendingCondition<VoucherSecret> condition;

    @BeforeEach
    void setUp() {
        Mint mockMint = Mockito.mock(Mint.class);
        Mockito.when(mockMint.getId()).thenReturn(MINT_ID);
        mockMintProtocolService = Mockito.mock(MintProtocolService.class);
        mockProofVaultService = Mockito.mock(ProofVaultService.class);
        condition = new VoucherSpendingCondition<>(mockMint, mockMintProtocolService, mockProofVaultService);
    }

    /**
     * The blob of a voucher captured from a real token, as a mutable map. Starting from a
     * genuine voucher means the signature is real, so a test that tampers with one field is
     * testing the signature check and not the shape of a hand-built fixture.
     */
    private static Map<String, Object> capturedBlob() {
        try (InputStream in = UnlockedVoucherSpendingConditionTest.class
                .getResourceAsStream("/captured-unlocked-voucher-secret.json")) {
            assertNotNull(in, "the captured unlocked voucher fixture must be on the classpath");
            WellKnownSecret secret = (WellKnownSecret) SecretUtil.toSecret(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).trim());
            return new LinkedHashMap<>(VoucherSerializationUtils.fromCbor(secret.getData()));
        } catch (IOException cannotRead) {
            throw new IllegalStateException(cannotRead);
        }
    }

    /** An unlocked voucher proof whose blob is exactly the given fields. */
    private static Proof<VoucherSecret> unlockedVoucherProof(Map<String, Object> blob) {
        return voucherProofWithData(Hex.toHexString(VoucherSerializationUtils.toCbor(blob)));
    }

    /**
     * A proof carrying an unlocked voucher secret with the given {@code data} hex, built from
     * the wire form so it parses exactly as one arriving at the mint does, empty tags included.
     */
    private static Proof<VoucherSecret> voucherProofWithData(String dataHex) {
        String wire = "[\"VOUCHER\",{\"nonce\":\"" + NONCE
                + "\",\"data\":\"" + dataHex + "\",\"tags\":[]}]";

        Proof<VoucherSecret> proof = new Proof<>();
        proof.setAmount(AMOUNT);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret((VoucherSecret) SecretUtil.toSecret(wire));
        proof.setUnblindedSignature(sampleSignature());
        return proof;
    }

    /** Everything the condition needs in order to reach the voucher checks and then pass. */
    private void stubHealthyProof() throws CashuErrorException {
        Mockito.when(mockProofVaultService.retrieveProof(any(), any(ProofSecret.class))).thenReturn(null);
        PrivateKey mockKey = Mockito.mock(PrivateKey.class);
        Mockito.when(mockKey.toBytes()).thenReturn(new byte[32]);
        Mockito.when(mockMintProtocolService.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(mockKey);
    }

    private CashuErrorException verifyExpectingRefusal(Proof<VoucherSecret> proof)
            throws CashuErrorException {
        stubHealthyProof();
        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            bdhke.when(() -> BDHKEUtils.verify(anyString(),
                            ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any()))
                    .thenReturn(true);
            return assertThrows(CashuErrorException.class, () -> condition.verify(proof));
        }
    }

    /**
     * The bug itself. A voucher whose signature belongs to a DIFFERENT voucher used to be
     * accepted, because the signature was in the blob and the check read tags. This is the
     * test that fails when the decode is removed.
     */
    @Test
    @DisplayName("an unlocked voucher whose face value was raised after signing is refused")
    void tamperedFaceValueIsRefused() throws CashuErrorException {
        Map<String, Object> blob = capturedBlob();
        long signedFaceValue = ((Number) blob.get("faceValue")).longValue();
        blob.put("faceValue", signedFaceValue * 100);

        CashuErrorException refusal = verifyExpectingRefusal(unlockedVoucherProof(blob));

        assertEquals("voucher_signature_invalid", refusal.getErrorCode().name(),
                "raising the face value after signing must not survive verification");
    }

    /** The same hole reached through the issuer: a coupon reassigned to another merchant. */
    @Test
    @DisplayName("an unlocked voucher reassigned to another issuer is refused")
    void tamperedIssuerIsRefused() throws CashuErrorException {
        Map<String, Object> blob = capturedBlob();
        blob.put("issuerId", "some-other-merchant");

        CashuErrorException refusal = verifyExpectingRefusal(unlockedVoucherProof(blob));

        assertEquals("voucher_signature_invalid", refusal.getErrorCode().name());
    }

    /**
     * Expiry was equally invisible, and it is checked BEFORE the signature, so this pins the
     * order as well as the check. A tampered expiry would otherwise read as a signature fault.
     */
    @Test
    @DisplayName("an expired unlocked voucher is refused as expired, not as unsigned")
    void expiredVoucherIsRefusedAsExpired() throws CashuErrorException {
        Map<String, Object> blob = capturedBlob();
        blob.put("expiresAt", 1L);

        CashuErrorException refusal = verifyExpectingRefusal(unlockedVoucherProof(blob));

        assertEquals("voucher_expired", refusal.getErrorCode().name(),
                "expiry is the more specific answer and must win");
    }

    /**
     * The other half of the claim, and the one a refuse-everything implementation would fail.
     * A genuine captured voucher must still verify, untouched.
     */
    @Test
    @DisplayName("a genuine unlocked voucher still verifies")
    void genuineVoucherVerifies() throws CashuErrorException {
        Proof<VoucherSecret> proof = unlockedVoucherProof(capturedBlob());
        stubHealthyProof();

        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            bdhke.when(() -> BDHKEUtils.verify(anyString(),
                            ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any()))
                    .thenReturn(true);
            assertDoesNotThrow(() -> condition.verify(proof),
                    "the fix must accept real vouchers, not merely refuse tampered ones");
        }
    }

    /**
     * A voucher-shaped secret carrying an unreadable blob. Nothing about it can be checked,
     * and the captured fixture shows a real one always reads, so this is either corruption or
     * an attempt to look like a voucher while being unverifiable.
     */
    @Test
    @DisplayName("an unreadable blob is refused rather than accepted unchecked")
    void unreadableBlobIsRefused() throws CashuErrorException {
        CashuErrorException refusal = verifyExpectingRefusal(voucherProofWithData("deadbeef"));

        assertEquals("voucher_signature_invalid", refusal.getErrorCode().name(),
                "a voucher the mint cannot read is a voucher the mint cannot honour");
    }

    /** A sanity check on the fixture, so a silently empty blob cannot make the rest vacuous. */
    @Test
    @DisplayName("the captured fixture really carries a signature and a face value")
    void fixtureCarriesWhatTheTestsTamperWith() {
        Map<String, Object> blob = capturedBlob();

        assertNotNull(blob.get("issuerSignature"), "otherwise the tamper tests prove nothing");
        assertNotNull(blob.get("issuerPublicKey"));
        assertNotNull(blob.get("faceValue"));
        assertNotNull(UUID.fromString((String) blob.get("voucherId")));
    }
}
