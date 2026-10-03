package xyz.tcheeric.cashu.mint.proto.tasks;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherIssuerBindingMode;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultProofVaultService;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherSignerTrustList;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedLockedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static xyz.tcheeric.cashu.mint.proto.util.SignatureTestData.sampleSignature;

/**
 * cashu-mint#527 end to end through the swap's proof verification: {@link VerifyProofsTask} picks
 * the spending condition, so these prove the issuer binding is reached on every voucher shape a
 * swap can carry, not only when a condition is built by hand.
 *
 * <p>Vouchers are signed for real with BIP-340 Schnorr. The unlocked ones are built the way the
 * customer gateway emits them on the wire: a CBOR blob in {@code data} and an empty tag array,
 * the shape whose signature went unchecked in #525. BDHKE and the proof vault are stubbed, because
 * the mint's own signature and the double-spend lookup are not what is under test.
 */
@DisplayName("a swap applies the voucher issuer binding to every voucher shape")
class VoucherIssuerBindingDispatchTest {

    private static final String KEYSET_ID = "0123456789abcdef";
    private static final String MERCHANT = "corner-cafe";

    private static final PrivateKey GATEWAY = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
    private static final PrivateKey ATTACKER = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
    private static final PrivateKey HOLDER = PrivateKey.fromBytes(Schnorr.generatePrivateKey());

    private MintProtocolService mintProtocolService;
    private MockedStatic<BDHKEUtils> bdhke;
    private MockedConstruction<DefaultProofVaultService> vault;

    @BeforeEach
    void setUp() throws CashuErrorException {
        mintProtocolService = Mockito.mock(MintProtocolService.class);
        PrivateKey keysetKey = Mockito.mock(PrivateKey.class);
        Mockito.when(keysetKey.toBytes()).thenReturn(new byte[32]);
        Mockito.when(mintProtocolService.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(keysetKey);
        bdhke = Mockito.mockStatic(BDHKEUtils.class);
        bdhke.when(() -> BDHKEUtils.verify(anyString(),
                        ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any()))
                .thenReturn(true);
        vault = Mockito.mockConstruction(DefaultProofVaultService.class);
    }

    @AfterEach
    void tearDown() {
        bdhke.close();
        vault.close();
    }

    /** A wire-format unlocked voucher signed by the trusted gateway swaps in enforce mode. */
    @Test
    void trustedBlobVoucherIsAccepted() {
        Proof<Secret> voucher = blobVoucher(signedPlainSecret(GATEWAY));

        assertDoesNotThrow(() -> swap(voucher, VoucherIssuerBindingMode.ENFORCE));
    }

    /** A wire-format unlocked voucher self-signed by an attacker is refused in enforce mode. */
    @Test
    void selfSignedBlobVoucherIsRefused() {
        Proof<Secret> forged = blobVoucher(signedPlainSecret(ATTACKER));

        CashuErrorException refusal = assertThrows(CashuErrorException.class,
                () -> swap(forged, VoucherIssuerBindingMode.ENFORCE));
        assertEquals("voucher_signature_invalid", refusal.getErrorCode().name());
    }

    /** A wire-format unlocked voucher with its signature stripped from the blob is refused in enforce mode. */
    @Test
    void unsignedBlobVoucherIsRefusedInEnforce() {
        Proof<Secret> stripped = blobVoucher(unsignedPlainSecret());

        CashuErrorException refusal = assertThrows(CashuErrorException.class,
                () -> swap(stripped, VoucherIssuerBindingMode.ENFORCE));
        assertEquals("voucher_signature_invalid", refusal.getErrorCode().name());
    }

    /** The same stripped blob voucher still swaps in log mode, as unsigned vouchers always have. */
    @Test
    void unsignedBlobVoucherIsAllowedInLog() {
        Proof<Secret> stripped = blobVoucher(unsignedPlainSecret());

        assertDoesNotThrow(() -> swap(stripped, VoucherIssuerBindingMode.LOG));
    }

    /** An in-memory tagged voucher dispatched by the task is refused when self-signed. */
    @Test
    void selfSignedTaggedVoucherIsRefused() {
        Proof<Secret> forged = proofOf(signedPlainSecret(ATTACKER));

        assertThrows(CashuErrorException.class, () -> swap(forged, VoucherIssuerBindingMode.ENFORCE));
    }

    /** A P2PK-locked voucher signed by the trusted gateway swaps in enforce mode, lock witness and all. */
    @Test
    void trustedLockedVoucherIsAccepted() {
        Proof<Secret> voucher = lockedVoucher(GATEWAY);

        assertDoesNotThrow(() -> swap(voucher, VoucherIssuerBindingMode.ENFORCE));
    }

    /** A P2PK-locked voucher self-signed by an attacker is refused in enforce mode despite a valid lock witness. */
    @Test
    void selfSignedLockedVoucherIsRefused() {
        Proof<Secret> forged = lockedVoucher(ATTACKER);

        CashuErrorException refusal = assertThrows(CashuErrorException.class,
                () -> swap(forged, VoucherIssuerBindingMode.ENFORCE));
        assertEquals("voucher_signature_invalid", refusal.getErrorCode().name());
    }

    /** A P2PK-locked voucher with no issuer signature is refused in enforce mode despite a valid lock witness. */
    @Test
    void unsignedLockedVoucherIsRefusedInEnforce() {
        Proof<Secret> stripped = lockedVoucher(null);

        CashuErrorException refusal = assertThrows(CashuErrorException.class,
                () -> swap(stripped, VoucherIssuerBindingMode.ENFORCE));
        assertEquals("voucher_signature_invalid", refusal.getErrorCode().name());
    }

    @SuppressWarnings("unchecked")
    private void swap(Proof<Secret> input, VoucherIssuerBindingMode mode) throws CashuErrorException {
        PostSwapRequest<Secret> request = Mockito.mock(PostSwapRequest.class);
        Mockito.when(request.getInputs()).thenReturn(List.of(input));
        Mockito.when(request.getBlindedMessages()).thenReturn(List.of(output()));
        VoucherIssuerBinding binding = new VoucherIssuerBinding(mode,
                new VoucherSignerTrustList(Map.of(), List.of(xOnly(GATEWAY))));
        new VerifyProofsTask<>(new Mint(UUID.randomUUID().toString()), request, mintProtocolService, binding)
                .execute();
    }

    private static VoucherSecret unsignedPlainSecret() {
        return VoucherSecret.builder()
                .voucherId(UUID.randomUUID())
                .issuerId(MERCHANT)
                .unit("sat")
                .faceValue(1000L)
                .backingStrategy(BackingStrategy.MINIMAL.name())
                .issuanceRatio(1.0d)
                .faceDecimals(0)
                .build();
    }

    private static VoucherSecret signedPlainSecret(PrivateKey signer) {
        VoucherSecret secret = unsignedPlainSecret();
        VoucherSignatureService.createSigned(secret, Hex.toHexString(signer.toBytes()), xOnly(signer));
        return secret;
    }

    /**
     * The voucher as the customer gateway puts it on the wire: its fields as a CBOR blob in
     * {@code data}, tags empty, parsed back exactly as the mint parses an arriving secret.
     */
    private static Proof<Secret> blobVoucher(VoucherSecret voucher) {
        Map<String, Object> blob = new LinkedHashMap<>();
        blob.put("voucherId", voucher.getVoucherId().toString());
        blob.put("issuerId", voucher.getIssuerId());
        blob.put("unit", voucher.getUnit());
        blob.put("faceValue", voucher.getFaceValue());
        blob.put("backingStrategy", voucher.getBackingStrategy());
        blob.put("issuanceRatio", voucher.getIssuanceRatio());
        blob.put("faceDecimals", voucher.getFaceDecimals());
        blob.put("nonce", voucher.getNonce());
        if (voucher.getIssuerSignature() != null) {
            blob.put("issuerSignature", voucher.getIssuerSignature());
            blob.put("issuerPublicKey", voucher.getIssuerPublicKey());
        }
        String wire = "[\"VOUCHER\",{\"nonce\":\"" + "00".repeat(16) + "\",\"data\":\""
                + Hex.toHexString(VoucherSerializationUtils.toCbor(blob)) + "\",\"tags\":[]}]";
        return proofOf(SecretUtil.toSecret(wire));
    }

    /** A locked voucher with a valid holder witness, signed by {@code signer}, or unsigned when null. */
    private static Proof<Secret> lockedVoucher(PrivateKey signer) {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(PrivateKey.derivePublicKey(HOLDER).getBytes());
        secret.setVoucherId(UUID.randomUUID().toString());
        secret.setIssuerId(MERCHANT);
        secret.setUnit("sat");
        secret.setFaceValue(1000L);
        secret.setNSigs(1);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
        if (signer != null) {
            SignedLockedVoucher.createSigned(secret, Hex.toHexString(signer.toBytes()), xOnly(signer));
        }
        Proof<Secret> proof = proofOf(secret);
        Witness witness = new Witness();
        witness.addSignature(Hex.toHexString(Schnorr.sign(sha256(secret.toString()), HOLDER.toBytes())));
        proof.setWitness(witness);
        return proof;
    }

    private static Proof<Secret> proofOf(Secret secret) {
        Proof<Secret> proof = new Proof<>();
        proof.setAmount(8);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret(secret);
        proof.setUnblindedSignature(sampleSignature());
        proof.setWitness(new Witness());
        return proof;
    }

    private static BlindedMessage output() {
        BlindedMessage message = new BlindedMessage();
        message.setAmount(8);
        message.setKeySetId(KeysetId.fromString(KEYSET_ID));
        message.setBlindedMessage(PublicKey.fromString(
                "02d963e52f9d2f9519f8adedc8517389293d8028e0b33c4bc96b5e3cd128c27af2"));
        message.setWitness(new Witness());
        return message;
    }

    private static String xOnly(PrivateKey key) {
        return Hex.toHexString(PrivateKey.derivePublicKey(key).getBytes()).substring(2);
    }

    private static byte[] sha256(String message) {
        try {
            return Utils.sha256(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException everyJvmHasIt) {
            throw new IllegalStateException(everyJvmHasIt);
        }
    }
}
