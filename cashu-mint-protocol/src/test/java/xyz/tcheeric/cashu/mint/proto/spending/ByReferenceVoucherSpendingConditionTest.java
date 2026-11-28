package xyz.tcheeric.cashu.mint.proto.spending;

import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SecretUtil;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherIssuerBindingMode;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.VoucherSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherDocumentContext;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherSignerTrustList;
import xyz.tcheeric.cashu.voucher.domain.VoucherDocuments;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;
import xyz.tcheeric.cashu.voucher.domain.util.VoucherSerializationUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * cashu-voucher#64: an unlocked voucher carried by reference (v2, {@code data = SHA256(D)}) gets
 * exactly the outcome its inline (v1, {@code data = D}) form gets, as long as the request carries
 * {@code D}. Without it, or with the wrong one, it is refused.
 *
 * <p>Nothing is mocked on the cryptographic path: the issuer signature is a real BIP-340
 * signature and the mint's C is a real BDHKE signature over the wire secret, so these tests also
 * pin which secret each check uses.
 */
@DisplayName("a by-reference voucher verifies exactly as its inline form does")
class ByReferenceVoucherSpendingConditionTest {

    private static final String MINT_ID = "1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae";
    private static final String KEYSET_ID = "00abc123def45678";
    private static final String MERCHANT = "corner-cafe";
    private static final String NONCE = "ab".repeat(16);
    private static final byte[] KEYSET_KEY = keysetKey();

    private static final KeyPair MERCHANT_KEY = KeyPair.generate();
    private static final KeyPair ATTACKER = KeyPair.generate();

    private Mint mint;
    private MintProtocolService mintProtocolService;
    private ProofVaultService proofVaultService;

    @BeforeEach
    void setUp() throws CashuErrorException {
        mint = Mockito.mock(Mint.class);
        Mockito.when(mint.getId()).thenReturn(MINT_ID);
        mintProtocolService = Mockito.mock(MintProtocolService.class);
        proofVaultService = Mockito.mock(ProofVaultService.class);
        PrivateKey key = Mockito.mock(PrivateKey.class);
        Mockito.when(key.toBytes()).thenReturn(KEYSET_KEY);
        Mockito.when(mintProtocolService.getPrivateKey(anyString(), anyInt(), any(Mint.class))).thenReturn(key);
    }

    /** The scenarios the inline suite already covers, each with the outcome it must keep. */
    enum Scenario {
        VALID(null, MERCHANT_KEY, v -> { }),
        EXPIRED(CashuErrorCode.voucher_expired, MERCHANT_KEY, v -> v.setExpiresAt(1L)),
        UNTRUSTED_SIGNER(CashuErrorCode.voucher_signature_invalid, ATTACKER, v -> { }),
        UNSIGNED_IN_ENFORCE(CashuErrorCode.voucher_signature_invalid, null, v -> { }),
        TAMPERED_AFTER_SIGNING(CashuErrorCode.voucher_signature_invalid, MERCHANT_KEY, v -> { });

        final CashuErrorCode expected;
        final KeyPair signer;
        final Consumer<VoucherSecret> shape;

        Scenario(CashuErrorCode expected, KeyPair signer, Consumer<VoucherSecret> shape) {
            this.expected = expected;
            this.signer = signer;
            this.shape = shape;
        }

        byte[] document() {
            VoucherSecret voucher = voucher();
            shape.accept(voucher);
            if (signer != null) {
                VoucherSignatureService.createSigned(voucher, signer.privateHex(), signer.xOnlyHex());
            }
            Map<String, Object> blob = blobOf(voucher);
            if (this == TAMPERED_AFTER_SIGNING) {
                blob.put("faceValue", 100_000L);
            }
            return VoucherSerializationUtils.toCbor(blob);
        }
    }

    // For every v1 scenario, the v2 form with its document reaches the same outcome as the v1 form.
    @ParameterizedTest
    @EnumSource(Scenario.class)
    @DisplayName("v2 with its document has the v1 outcome")
    void v2WithItsDocumentMatchesV1(Scenario scenario) throws Exception {
        byte[] document = scenario.document();

        CashuErrorCode inline = outcome(inlineProof(document), VoucherDocumentContext.none());
        CashuErrorCode byReference = outcome(referenceProof(document), contextWith(document));

        assertThat(inline).isEqualTo(scenario.expected);
        assertThat(byReference).isEqualTo(inline);
    }

    // A v2 proof whose request carries no documents is refused as a document fault, never verified.
    @Test
    @DisplayName("v2 without its document is refused with 90032")
    void v2WithoutItsDocumentIsRefused() throws Exception {
        byte[] document = Scenario.VALID.document();

        assertThat(outcome(referenceProof(document), VoucherDocumentContext.none()))
                .isEqualTo(CashuErrorCode.voucher_document_invalid);
    }

    // A v2 proof whose request carries a different (valid) document is refused: lookup is by its own hash.
    @Test
    @DisplayName("v2 with another voucher's document is refused with 90032")
    void v2WithAWrongDocumentIsRefused() throws Exception {
        byte[] document = Scenario.VALID.document();
        byte[] other = Scenario.EXPIRED.document();

        assertThat(outcome(referenceProof(document), contextWith(other)))
                .isEqualTo(CashuErrorCode.voucher_document_invalid);
    }

    // A v1 proof is unaffected by documents it does not reference: they are ignored.
    @Test
    @DisplayName("unreferenced documents are ignored by an inline proof")
    void unreferencedDocumentsAreIgnored() throws Exception {
        byte[] document = Scenario.VALID.document();

        assertThat(outcome(inlineProof(document), contextWith(Scenario.EXPIRED.document()))).isNull();
    }

    // The mint's C was signed over the v2 wire secret, so BDHKE must check the wire secret, not the document.
    @Test
    @DisplayName("BDHKE verifies against the wire (v2) secret")
    void bdhkeUsesTheWireSecret() throws Exception {
        byte[] document = Scenario.VALID.document();
        Proof<VoucherSecret> proof = referenceProof(document);
        assertThat(outcome(proof, contextWith(document))).isNull();

        Proof<VoucherSecret> signedOverTheDocument = referenceProof(document);
        signedOverTheDocument.setUnblindedSignature(mintSignature(inlineProof(document).getSecret().toString()));
        assertThat(outcome(signedOverTheDocument, contextWith(document)))
                .isEqualTo(CashuErrorCode.verify_proof_failed_error);
    }

    // Double-spend state is checked by the swap's hold, keyed on the v2 wire secret (its Y), so v1
    // and v2 of one voucher are distinct proofs. The condition itself no longer consults the vault:
    // SwapProofHoldReportsSpentInputTest.voucherHoldIsKeyedOnTheWireSecret pins the key.
    @Test
    @DisplayName("the condition does not look the proof up in the vault")
    void conditionDoesNotConsultTheVault() throws Exception {
        byte[] document = Scenario.VALID.document();
        Proof<VoucherSecret> proof = referenceProof(document);

        outcome(proof, contextWith(document));

        Mockito.verifyNoInteractions(proofVaultService);
    }

    private CashuErrorCode outcome(Proof<VoucherSecret> proof, VoucherDocumentContext documents) {
        VoucherSpendingCondition<VoucherSecret> condition = new VoucherSpendingCondition<>(
                mint, mintProtocolService, proofVaultService, enforcing(), documents);
        try {
            condition.verify(proof);
            return null;
        } catch (CashuErrorException refused) {
            return refused.getErrorCode();
        }
    }

    static VoucherDocumentContext contextWith(byte[]... documents) throws CashuErrorException {
        return VoucherDocumentContext.of(ByReferenceRequests.swapCarrying(documents.length + 1, documents));
    }

    private static VoucherIssuerBinding enforcing() {
        return new VoucherIssuerBinding(VoucherIssuerBindingMode.ENFORCE,
                new VoucherSignerTrustList(Map.of(MERCHANT, MERCHANT_KEY.xOnlyHex()), List.of()));
    }

    static VoucherSecret voucher() {
        return VoucherSecret.builder()
                .voucherId(UUID.randomUUID())
                .issuerId(MERCHANT)
                .unit("sat")
                .faceValue(1000L)
                .backingStrategy("MINIMAL")
                .build();
    }

    /** The CBOR blob an inline voucher carries, in the field names the mint's reader expects. */
    static Map<String, Object> blobOf(VoucherSecret v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("voucherId", v.getVoucherId().toString());
        m.put("issuerId", v.getIssuerId());
        m.put("unit", v.getUnit());
        m.put("faceValue", v.getFaceValue());
        putIfPresent(m, "expiresAt", v.getExpiresAt());
        putIfPresent(m, "backingStrategy", v.getBackingStrategy());
        m.put("issuanceRatio", v.getIssuanceRatio());
        m.put("faceDecimals", v.getFaceDecimals());
        putIfPresent(m, "nonce", v.getNonce());
        putIfPresent(m, "issuerSignature", v.getIssuerSignature());
        putIfPresent(m, "issuerPublicKey", v.getIssuerPublicKey());
        return m;
    }

    private static void putIfPresent(Map<String, Object> m, String key, Object value) {
        if (value != null) {
            m.put(key, value);
        }
    }

    private Proof<VoucherSecret> inlineProof(byte[] document) {
        return proofWithData(document);
    }

    private Proof<VoucherSecret> referenceProof(byte[] document) {
        return proofWithData(Hex.decode(VoucherDocuments.key(document)));
    }

    /** A proof as it arrives on the wire, with a real mint signature over that wire secret. */
    static Proof<VoucherSecret> proofWithData(byte[] data) {
        String wire = "[\"VOUCHER\",{\"nonce\":\"" + NONCE + "\",\"data\":\"" + Hex.toHexString(data)
                + "\",\"tags\":[]}]";
        Proof<VoucherSecret> proof = new Proof<>();
        proof.setAmount(8);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret((VoucherSecret) SecretUtil.toSecret(wire));
        proof.setUnblindedSignature(mintSignature(proof.getSecret().toString()));
        return proof;
    }

    /** C = k * hash_to_curve(secret): the unblinded signature the mint would have issued. */
    static Signature mintSignature(String secret) {
        byte[] y = BDHKEUtils.hashToCurve(secret);
        return Signature.fromBytes(BDHKEUtils.signBlindedMessage(y, KEYSET_KEY));
    }

    private static byte[] keysetKey() {
        byte[] k = new byte[32];
        k[31] = 7;
        k[0] = 1;
        return k;
    }

    private record KeyPair(byte[] privateKey, byte[] compressed) {

        static KeyPair generate() {
            PrivateKey key = PrivateKey.fromBytes(Schnorr.generatePrivateKey());
            return new KeyPair(key.toBytes(), PrivateKey.derivePublicKey(key).getBytes());
        }

        String privateHex() {
            return Hex.toHexString(privateKey);
        }

        String xOnlyHex() {
            return Hex.toHexString(compressed).substring(2);
        }
    }
}
