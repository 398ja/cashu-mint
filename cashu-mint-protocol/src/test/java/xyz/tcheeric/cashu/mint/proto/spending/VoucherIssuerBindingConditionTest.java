package xyz.tcheeric.cashu.mint.proto.spending;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.Witness;
import xyz.tcheeric.cashu.common.nut11.P2PKSecret;
import xyz.tcheeric.cashu.common.nut11.P2PKVoucherSecret;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.crypto.Schnorr;
import xyz.tcheeric.cashu.crypto.util.Utils;
import xyz.tcheeric.cashu.mint.proto.crypto.ProofSecret;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherFundingSource;
import xyz.tcheeric.cashu.mint.proto.metrics.MetricRecorders;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherMetricsRecorder;
import xyz.tcheeric.cashu.mint.proto.metrics.VoucherRejectionReason;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKTransaction;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.P2PKVoucherSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.tasks.validator.VoucherSpendingCondition;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherIssuerBinding;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherIssuerBindingMode;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherSignerTrustList;
import xyz.tcheeric.cashu.voucher.domain.SignedLockedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSignatureService;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static xyz.tcheeric.cashu.mint.proto.util.SignatureTestData.sampleSignature;

/**
 * cashu-mint#527: a voucher's signature is verified against the key the voucher itself names, so
 * the mint must also ask whether that key is one it trusts for the voucher's issuer.
 *
 * <p>Every voucher here is signed for real with BIP-340 Schnorr, so a passing signature check is
 * a genuine one and the only thing under test is the binding between signer and issuer. BDHKE is
 * stubbed because the mint's own signature on the proof is not what this is about.
 */
@DisplayName("a voucher's signer must be trusted for the issuer it names")
class VoucherIssuerBindingConditionTest {

    private static final String MINT_ID = "1f240ace-0e4e-42dd-bdcb-9ad4ce8eaeae";
    private static final String KEYSET_ID = "00abc123def45678";
    private static final String MERCHANT = "corner-cafe";

    private static final KeyPair GATEWAY = KeyPair.generate();
    private static final KeyPair MERCHANT_KEY = KeyPair.generate();
    private static final KeyPair ATTACKER = KeyPair.generate();
    private static final KeyPair HOLDER = KeyPair.generate();

    private Mint mint;
    private MintProtocolService mintProtocolService;
    private ProofVaultService proofVaultService;
    private RecordingVoucherRecorder recorder;
    private ListAppender<ILoggingEvent> bindingLog;

    @BeforeEach
    void setUp() throws CashuErrorException {
        mint = Mockito.mock(Mint.class);
        Mockito.when(mint.getId()).thenReturn(MINT_ID);
        mintProtocolService = Mockito.mock(MintProtocolService.class);
        proofVaultService = Mockito.mock(ProofVaultService.class);
        Mockito.when(proofVaultService.retrieveProof(any(), any(ProofSecret.class))).thenReturn(null);
        PrivateKey keysetKey = Mockito.mock(PrivateKey.class);
        Mockito.when(keysetKey.toBytes()).thenReturn(new byte[32]);
        Mockito.when(mintProtocolService.getPrivateKey(anyString(), anyInt(), any(Mint.class)))
                .thenReturn(keysetKey);

        recorder = new RecordingVoucherRecorder(new ArrayList<>(), new ArrayList<>());
        MetricRecorders.registerVoucher(recorder);
        bindingLog = new ListAppender<>();
        bindingLog.start();
        bindingLogger().addAppender(bindingLog);
    }

    @AfterEach
    void tearDown() {
        MetricRecorders.registerVoucher(null);
        bindingLogger().detachAppender(bindingLog);
    }

    @Nested
    @DisplayName("a self-signed voucher: attacker key, merchant issuerId")
    class SelfSigned {

        /** Enforce mode refuses it with the error a forged signature gets. */
        @Test
        void isRefusedInEnforce() {
            Proof<VoucherSecret> forged = unlockedVoucher(MERCHANT, ATTACKER);

            CashuErrorException refusal = assertThrows(CashuErrorException.class,
                    () -> verify(forged, binding(VoucherIssuerBindingMode.ENFORCE)));

            assertEquals("voucher_signature_invalid", refusal.getErrorCode().name());
            assertThat(recorder.untrusted()).containsExactly(VoucherIssuerBindingMode.ENFORCE);
        }

        /** Log mode lets it through, but reports it so an operator can see what enforce would refuse. */
        @Test
        void isAllowedAndReportedInLog() {
            Proof<VoucherSecret> forged = unlockedVoucher(MERCHANT, ATTACKER);

            assertDoesNotThrow(() -> verify(forged, binding(VoucherIssuerBindingMode.LOG)));

            assertThat(untrustedLines()).hasSize(1);
            assertThat(recorder.untrusted()).containsExactly(VoucherIssuerBindingMode.LOG);
        }

        /** The report names neither the full signing key nor the raw issuer id. */
        @Test
        void isReportedWithoutFullKeysOrIssuerIds() {
            Proof<VoucherSecret> forged = unlockedVoucher(MERCHANT, ATTACKER);

            assertDoesNotThrow(() -> verify(forged, binding(VoucherIssuerBindingMode.LOG)));

            String line = untrustedLines().get(0);
            assertThat(line).contains("signerPrefix=" + ATTACKER.xOnlyHex().substring(0, 12));
            assertThat(line).doesNotContain(ATTACKER.xOnlyHex());
            assertThat(line).doesNotContain(MERCHANT);
            assertThat(line).doesNotContain(forged.getSecret().getIssuerSignature());
        }

        /** Off mode does not look at the signer at all. */
        @Test
        void isUntouchedInOff() {
            Proof<VoucherSecret> forged = unlockedVoucher(MERCHANT, ATTACKER);

            assertDoesNotThrow(() -> verify(forged, binding(VoucherIssuerBindingMode.OFF)));

            assertThat(untrustedLines()).isEmpty();
            assertThat(recorder.untrusted()).isEmpty();
        }
    }

    @Nested
    @DisplayName("a legitimately signed voucher in enforce mode")
    class Legitimate {

        /** The gateway signs for the merchant it issues on behalf of, and is configured as a trusted signer. */
        @Test
        void signedByATrustedSignerForAMerchantIsAccepted() {
            Proof<VoucherSecret> voucher = unlockedVoucher(MERCHANT, GATEWAY);

            assertDoesNotThrow(() -> verify(voucher, binding(VoucherIssuerBindingMode.ENFORCE)));
            assertThat(recorder.untrusted()).isEmpty();
        }

        /** A merchant that signs its own vouchers is trusted through its registered key. */
        @Test
        void signedByTheKeyRegisteredForItsIssuerIsAccepted() {
            Proof<VoucherSecret> voucher = unlockedVoucher(MERCHANT, MERCHANT_KEY);

            assertDoesNotThrow(() -> verify(voucher, binding(VoucherIssuerBindingMode.ENFORCE)));
        }

        /** Issuer ids match case-insensitively, as the merchant-verification registry always did. */
        @Test
        void issuerIdMatchesWhateverItsCase() {
            Proof<VoucherSecret> voucher =
                    unlockedVoucher(MERCHANT.toUpperCase(Locale.ROOT), MERCHANT_KEY);

            assertDoesNotThrow(() -> verify(voucher, binding(VoucherIssuerBindingMode.ENFORCE)));
        }

        /** A key registered for one issuer does not cover vouchers naming another. */
        @Test
        void aRegisteredKeyDoesNotCoverAnotherIssuer() {
            Proof<VoucherSecret> voucher = unlockedVoucher("rival-bakery", MERCHANT_KEY);

            CashuErrorException refusal = assertThrows(CashuErrorException.class,
                    () -> verify(voucher, binding(VoucherIssuerBindingMode.ENFORCE)));
            assertEquals("voucher_signature_invalid", refusal.getErrorCode().name());
        }
    }

    @Nested
    @DisplayName("configured keys match whatever their encoding")
    class KeyEncodings {

        /** A trusted signer written compressed, either parity, upper or lower case, matches the x-only key a voucher carries. */
        @ParameterizedTest
        @ValueSource(strings = {"02", "03", ""})
        void trustedSignerMatchesInAnyEncodingAndCase(String prefix) {
            String configured = (prefix + GATEWAY.xOnlyHex()).toUpperCase(Locale.ROOT);
            VoucherIssuerBinding binding = new VoucherIssuerBinding(VoucherIssuerBindingMode.ENFORCE,
                    new VoucherSignerTrustList(Map.of(), List.of(configured)));

            assertDoesNotThrow(() -> verify(unlockedVoucher(MERCHANT, GATEWAY), binding));
        }

        /** The same holds for a key registered for one issuer. */
        @ParameterizedTest
        @ValueSource(strings = {"02", "03", ""})
        void issuerKeyMatchesInAnyEncodingAndCase(String prefix) {
            String configured = (prefix + MERCHANT_KEY.xOnlyHex()).toUpperCase(Locale.ROOT);
            VoucherIssuerBinding binding = new VoucherIssuerBinding(VoucherIssuerBindingMode.ENFORCE,
                    new VoucherSignerTrustList(Map.of(MERCHANT, configured), List.of()));

            assertDoesNotThrow(() -> verify(unlockedVoucher(MERCHANT, MERCHANT_KEY), binding));
        }
    }

    @Nested
    @DisplayName("a P2PK-locked voucher gets the same treatment")
    class LockedVoucher {

        /** A self-signed locked voucher is refused in enforce mode, even with a valid lock witness. */
        @Test
        void selfSignedIsRefusedInEnforce() {
            Proof<P2PKVoucherSecret> forged = lockedVoucher(MERCHANT, ATTACKER);

            CashuErrorException refusal = assertThrows(CashuErrorException.class,
                    () -> verifyLocked(forged, binding(VoucherIssuerBindingMode.ENFORCE)));
            assertEquals("voucher_signature_invalid", refusal.getErrorCode().name());
        }

        /** A self-signed locked voucher is allowed and reported in log mode. */
        @Test
        void selfSignedIsReportedInLog() {
            Proof<P2PKVoucherSecret> forged = lockedVoucher(MERCHANT, ATTACKER);

            assertDoesNotThrow(() -> verifyLocked(forged, binding(VoucherIssuerBindingMode.LOG)));
            assertThat(recorder.untrusted()).containsExactly(VoucherIssuerBindingMode.LOG);
        }

        /** A locked voucher signed by the trusted gateway is accepted in enforce mode. */
        @Test
        void signedByATrustedSignerIsAccepted() {
            Proof<P2PKVoucherSecret> voucher = lockedVoucher(MERCHANT, GATEWAY);

            assertDoesNotThrow(() -> verifyLocked(voucher, binding(VoucherIssuerBindingMode.ENFORCE)));
        }
    }

    @Nested
    @DisplayName("an unsigned voucher")
    class Unsigned {

        /** Stripping the signature must not bypass enforce: an unsigned plain voucher is refused. */
        @Test
        void plainIsRefusedInEnforce() {
            Proof<VoucherSecret> unsigned = voucherProof(voucherSecret(MERCHANT));

            CashuErrorException refusal = assertThrows(CashuErrorException.class,
                    () -> verify(unsigned, binding(VoucherIssuerBindingMode.ENFORCE)));
            assertEquals("voucher_signature_invalid", refusal.getErrorCode().name());
            assertThat(recorder.unsigned()).containsExactly(VoucherIssuerBindingMode.ENFORCE);
        }

        /** The same holds for a P2PK-locked voucher with its signature stripped but a valid lock witness. */
        @Test
        void lockedIsRefusedInEnforce() {
            Proof<P2PKVoucherSecret> unsigned = unsignedLockedVoucher(MERCHANT);

            CashuErrorException refusal = assertThrows(CashuErrorException.class,
                    () -> verifyLocked(unsigned, binding(VoucherIssuerBindingMode.ENFORCE)));
            assertEquals("voucher_signature_invalid", refusal.getErrorCode().name());
        }

        /** Log mode lets an unsigned voucher through but logs it distinctly and counts it. */
        @Test
        void isAllowedAndReportedDistinctlyInLog() {
            Proof<VoucherSecret> unsigned = voucherProof(voucherSecret(MERCHANT));

            assertDoesNotThrow(() -> verify(unsigned, binding(VoucherIssuerBindingMode.LOG)));

            assertThat(linesStartingWith("voucher_unsigned")).hasSize(1);
            assertThat(linesStartingWith("voucher_unsigned").get(0)).doesNotContain(MERCHANT);
            assertThat(untrustedLines()).isEmpty();
            assertThat(recorder.unsigned()).containsExactly(VoucherIssuerBindingMode.LOG);
            assertThat(recorder.untrusted()).isEmpty();
        }

        /** Off mode leaves an unsigned voucher exactly as it was before the binding existed. */
        @Test
        void isUntouchedInOff() {
            Proof<VoucherSecret> unsigned = voucherProof(voucherSecret(MERCHANT));

            assertDoesNotThrow(() -> verify(unsigned, binding(VoucherIssuerBindingMode.OFF)));
            assertThat(linesStartingWith("voucher_unsigned")).isEmpty();
            assertThat(recorder.unsigned()).isEmpty();
        }
    }

    @Nested
    @DisplayName("ordering")
    class Ordering {

        /** A forged signature is refused by the signature check and never reaches the binding, even from a trusted key. */
        @Test
        void aTamperedVoucherFailsTheSignatureCheckNotTheBinding() {
            Proof<VoucherSecret> voucher = unlockedVoucher(MERCHANT, GATEWAY);
            ((VoucherSecret) voucher.getSecret()).setFaceValue(1_000_000L);

            CashuErrorException refusal = assertThrows(CashuErrorException.class,
                    () -> verify(voucher, binding(VoucherIssuerBindingMode.LOG)));
            assertEquals("voucher_signature_invalid", refusal.getErrorCode().name());
            assertThat(recorder.untrusted()).isEmpty();
        }
    }

    private static VoucherIssuerBinding binding(VoucherIssuerBindingMode mode) {
        return new VoucherIssuerBinding(mode, new VoucherSignerTrustList(
                Map.of(MERCHANT, MERCHANT_KEY.xOnlyHex()), List.of(GATEWAY.xOnlyHex())));
    }

    private void verify(Proof<VoucherSecret> proof, VoucherIssuerBinding binding) throws CashuErrorException {
        VoucherSpendingCondition<VoucherSecret> condition =
                new VoucherSpendingCondition<>(mint, mintProtocolService, proofVaultService, binding);
        withGenuineBdhke(() -> condition.verify(proof));
    }

    private void verifyLocked(Proof<P2PKVoucherSecret> proof, VoucherIssuerBinding binding)
            throws CashuErrorException {
        VoucherSpendingCondition<P2PKVoucherSecret> voucherHalf =
                new VoucherSpendingCondition<>(mint, mintProtocolService, proofVaultService, binding);
        P2PKVoucherSpendingCondition<P2PKVoucherSecret> condition = new P2PKVoucherSpendingCondition<>(
                voucherHalf, P2PKTransaction.forSwap(List.of(proof), List.of()));
        withGenuineBdhke(() -> condition.verify(proof));
    }

    private static void withGenuineBdhke(Verification verification) throws CashuErrorException {
        try (MockedStatic<BDHKEUtils> bdhke = Mockito.mockStatic(BDHKEUtils.class)) {
            bdhke.when(() -> BDHKEUtils.verify(anyString(),
                            ArgumentMatchers.<byte[]>any(), ArgumentMatchers.<byte[]>any()))
                    .thenReturn(true);
            verification.run();
        }
    }

    private static VoucherSecret voucherSecret(String issuerId) {
        return VoucherSecret.builder()
                .voucherId(UUID.randomUUID())
                .issuerId(issuerId)
                .unit("sat")
                .faceValue(1000L)
                .build();
    }

    private static Proof<VoucherSecret> unlockedVoucher(String issuerId, KeyPair signer) {
        VoucherSecret secret = voucherSecret(issuerId);
        VoucherSignatureService.createSigned(secret, signer.privateHex(), signer.xOnlyHex());
        return voucherProof(secret);
    }

    private static Proof<VoucherSecret> voucherProof(VoucherSecret secret) {
        Proof<VoucherSecret> proof = new Proof<>();
        proof.setAmount(8);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret(secret);
        proof.setUnblindedSignature(sampleSignature());
        return proof;
    }

    /** A locked voucher signed by {@code signer} and carrying a valid NUT-11 witness from the holder. */
    private static Proof<P2PKVoucherSecret> lockedVoucher(String issuerId, KeyPair signer) {
        P2PKVoucherSecret secret = lockedSecret(issuerId);
        SignedLockedVoucher.createSigned(secret, signer.privateHex(), signer.xOnlyHex());
        return lockedProof(secret);
    }

    /** A locked voucher with no issuer signature at all, but a valid lock witness. */
    private static Proof<P2PKVoucherSecret> unsignedLockedVoucher(String issuerId) {
        return lockedProof(lockedSecret(issuerId));
    }

    private static P2PKVoucherSecret lockedSecret(String issuerId) {
        P2PKVoucherSecret secret = new P2PKVoucherSecret(HOLDER.compressed());
        secret.setVoucherId(UUID.randomUUID().toString());
        secret.setIssuerId(issuerId);
        secret.setUnit("sat");
        secret.setFaceValue(1000L);
        secret.setNSigs(1);
        secret.setSigFlag(P2PKSecret.SignatureFlag.SIG_INPUTS);
        return secret;
    }

    private static Proof<P2PKVoucherSecret> lockedProof(P2PKVoucherSecret secret) {
        Witness witness = new Witness();
        witness.addSignature(Hex.toHexString(Schnorr.sign(sha256(secret.toString()), HOLDER.privateKey())));

        Proof<P2PKVoucherSecret> proof = new Proof<>();
        proof.setAmount(8);
        proof.setKeySetId(KEYSET_ID);
        proof.setSecret(secret);
        proof.setWitness(witness);
        proof.setUnblindedSignature(sampleSignature());
        return proof;
    }

    private static byte[] sha256(String message) {
        try {
            return Utils.sha256(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException everyJvmHasIt) {
            throw new IllegalStateException(everyJvmHasIt);
        }
    }

    private List<String> untrustedLines() {
        return linesStartingWith("voucher_issuer_untrusted");
    }

    private List<String> linesStartingWith(String event) {
        return bindingLog.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith(event))
                .toList();
    }

    private static Logger bindingLogger() {
        return (Logger) LoggerFactory.getLogger(VoucherIssuerBinding.class);
    }

    @FunctionalInterface
    private interface Verification {
        void run() throws CashuErrorException;
    }

    /** A secp256k1 keypair in the encodings the tests need. */
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

    private record RecordingVoucherRecorder(List<VoucherIssuerBindingMode> untrusted,
                                            List<VoucherIssuerBindingMode> unsigned)
            implements VoucherMetricsRecorder {
        @Override public void rejected(VoucherRejectionReason reason) { }
        @Override public void issued(VoucherFundingSource fundingSource) { }
        @Override public void iouIssuanceAttempted() { }
        @Override public void lazyFundingCreated() { }
        @Override public void fundingReconciled(boolean recovered) { }
        @Override public void rateLimitBreach() { }
        @Override public void issuerUntrusted(VoucherIssuerBindingMode mode) { untrusted.add(mode); }
        @Override public void unsignedVoucher(VoucherIssuerBindingMode mode) { unsigned.add(mode); }
    }
}
