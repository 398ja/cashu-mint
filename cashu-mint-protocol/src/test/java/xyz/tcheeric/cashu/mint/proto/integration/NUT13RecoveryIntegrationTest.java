package xyz.tcheeric.cashu.mint.proto.integration;

import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.crypto.HDKeyDerivation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.bips.bip32.nut.Nut13Derivation;
import xyz.tcheeric.bips.bip39.Bip39;
import xyz.tcheeric.cashu.common.*;
import xyz.tcheeric.cashu.common.nut13.DeterministicSecret;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintResponse;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.nut09.PostRestoreResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT09;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests for NUT-13 deterministic wallet recovery.
 *
 * <p>These tests verify the complete recovery flow:
 * <ol>
 *   <li>Derive deterministic secrets from BIP39 mnemonic</li>
 *   <li>Mint tokens using deterministic secrets</li>
 *   <li>Simulate wallet loss (clear state)</li>
 *   <li>Recover tokens by deriving same secrets from mnemonic</li>
 *   <li>Verify all signatures are recovered correctly</li>
 * </ol>
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/13.md">NUT-13 Specification</a>
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/09.md">NUT-09 Restore Specification</a>
 */
@DisplayName("NUT-13 End-to-End Recovery Integration Tests")
public class NUT13RecoveryIntegrationTest {

    private static final String TEST_KEYSET_ID = "009a1f293253e41e";
    private static final String TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private static final String TEST_PASSPHRASE = "";

    private static final int SECONDARY_KEY_OFFSET = 2;

    private SignatureVaultService vault;
    private MintProtocolService protocolService;
    private MintLoadService mintLoadService;
    private Gateway gateway;

    private static PublicKey deriveTestPublicKey(DeterministicKey masterKey, int counter) {
        DeterministicKey childKey = HDKeyDerivation.deriveChildKey(masterKey, new ChildNumber(counter, false));
        String compressedHex = childKey.getPublicKeyAsHex().toLowerCase(Locale.ROOT);
        return PublicKey.fromString(compressedHex);
    }

    @BeforeEach
    void setUp() throws CashuErrorException {
        vault = new DefaultSignatureVaultService();

        // Mock gateway
        gateway = Mockito.mock(Gateway.class);
        Mockito.when(gateway.getAmount(Mockito.anyString())).thenReturn(100);
        Mockito.when(gateway.checkPaymentStatus(Mockito.anyString())).thenReturn(true);

        // Mock protocol service
        protocolService = Mockito.mock(MintProtocolService.class);
        Mockito.when(protocolService.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);
        Mockito.when(protocolService.getPrivateKey(Mockito.anyString(), Mockito.anyInt(), Mockito.any()))
                .thenReturn(PrivateKey.fromString("a98675fc698aa718496e533de19d9d6bfb9c3bc9648e6ac9ad8416599881b3b5"));

        // Mock mint load service with a Mint that has keyset(s) configured
        mintLoadService = Mockito.mock(MintLoadService.class);
        Mint mint = new Mint();
        Keys keys = new Keys();
        // Standard Cashu power-of-2 denominations
        for (int exp = 0; exp <= 7; exp++) {
            BigInteger denom = BigInteger.valueOf(1L << exp);
            keys.put(denom, PrivateKey.derivePublicKey(PrivateKey.fromString(
                    String.format("%064x", denom.longValue()))));
        }
        mint.addKeySet(KeySet.builder().id(TEST_KEYSET_ID).unit("sat").keys(keys).build());
        // Secondary keyset used by testRecoveryWithMultipleKeysets
        mint.addKeySet(KeySet.builder().id("00ad268c4d1f5826").unit("sat").keys(keys).build());
        Mockito.when(mintLoadService.load(Mockito.any(UUID.class), Mockito.eq(false))).thenReturn(mint);
    }

    /**
     * Tests the complete NUT-13 recovery flow: mint tokens with deterministic secrets,
     * then recover them using the same mnemonic. This simulates a user restoring their
     * wallet from a 12-word backup phrase.
     */
    @Test
    @DisplayName("Should recover all minted tokens from mnemonic phrase")
    void testFullRecoveryFlow() throws CashuErrorException {
        // Arrange: Derive master key from mnemonic
        DeterministicKey masterKey = Bip39.mnemonicToMasterKey(TEST_MNEMONIC, TEST_PASSPHRASE);
        KeysetId keysetId = KeysetId.fromString(TEST_KEYSET_ID);

        // Derive 3 deterministic secrets at counter positions 0, 1, 2
        List<DeterministicSecret> secrets = new ArrayList<>();
        List<byte[]> blindingFactors = new ArrayList<>();
        List<BlindedMessage> blindedMessages = new ArrayList<>();

        for (int counter = 0; counter < 3; counter++) {
            var pair = Nut13Derivation.deriveSecretAndBlindingFactor(
                    masterKey,
                    TEST_KEYSET_ID,
                    counter
            );

            DeterministicSecret secret = DeterministicSecret.create(
                    pair.secret(),
                    keysetId,
                    counter
            );

            secrets.add(secret);
            blindingFactors.add(pair.blindingFactor());

            // Create blinded message from deterministic values
            // Note: In real scenario, would use proper BDHKE blinding
            // Using valid SECP256K1 public keys for testing
            BlindedMessage bm = BlindedMessage.builder()
                    .amount(8)
                    .keySetId(keysetId)
                    .blindedMessage(deriveTestPublicKey(masterKey, counter))
                    .build();
            blindedMessages.add(bm);
        }

        // Act 1: Mint tokens using deterministic secrets
        List<PostMintResponse> mintResponses = new ArrayList<>();
        for (int i = 0; i < secrets.size(); i++) {
            PostMintRequest<Secret> mintRequest = new PostMintRequest<>(
                    UUID.randomUUID().toString(),
                    List.of(blindedMessages.get(i)),
                    List.of(secrets.get(i)),
                    List.of(blindingFactors.get(i))
            );

            PostMintResponse mintResponse = NUT04.mint(
                    UUID.randomUUID(),
                    mintRequest,
                    PaymentMethod.MOCK,
                    null,
                    mintLoadService,
                    protocolService,
                    vault
            );

            mintResponses.add(mintResponse);
        }

        // Simulate wallet loss - local state cleared, but vault still has signatures

        // Act 2: Recover wallet by deriving same secrets from same mnemonic
        DeterministicKey recoveredMasterKey = Bip39.mnemonicToMasterKey(TEST_MNEMONIC, TEST_PASSPHRASE);

        List<BlindedMessage> recoveredBlindedMessages = new ArrayList<>();
        for (int counter = 0; counter < 3; counter++) {
            var pair = Nut13Derivation.deriveSecretAndBlindingFactor(
                    recoveredMasterKey,
                    TEST_KEYSET_ID,
                    counter
            );

            // Same secret produces same blinded message
            BlindedMessage bm = BlindedMessage.builder()
                    .amount(8)
                    .keySetId(keysetId)
                    .blindedMessage(deriveTestPublicKey(recoveredMasterKey, counter))
                    .build();
            recoveredBlindedMessages.add(bm);
        }

        PostRestoreRequest restoreRequest = new PostRestoreRequest(recoveredBlindedMessages);
        PostRestoreResponse restoreResponse = NUT09.restore(restoreRequest, vault);

        // Assert: All signatures recovered
        assertEquals(3, restoreResponse.getBlindSignatures().size(),
                "Should recover all 3 minted signatures");
        assertEquals(3, restoreResponse.getBlindedMessages().size(),
                "Should return all 3 blinded messages");

        // Verify recovered signatures match original minted signatures
        for (int i = 0; i < 3; i++) {
            assertEquals(
                    mintResponses.get(i).getBlindSignatures().get(0),
                    restoreResponse.getBlindSignatures().get(i),
                    "Recovered signature " + i + " should match original"
            );
        }
    }

    /**
     * Tests NUT-13 recovery with gaps in the counter sequence. This simulates a scenario
     * where a user minted tokens at counters 0, 2, 4 (skipping 1, 3). The recovery process
     * should detect these gaps and continue searching.
     */
    @Test
    @DisplayName("Should handle gaps in counter sequence during recovery")
    void testRecoveryWithGaps() throws CashuErrorException {
        // Arrange: Mint tokens at non-sequential counters (0, 2, 4)
        DeterministicKey masterKey = Bip39.mnemonicToMasterKey(TEST_MNEMONIC, TEST_PASSPHRASE);
        KeysetId keysetId = KeysetId.fromString(TEST_KEYSET_ID);

        int[] mintedCounters = {0, 2, 4}; // Gaps at 1 and 3

        List<BlindedMessage> mintedMessages = new ArrayList<>();
        for (int counter : mintedCounters) {
            var pair = Nut13Derivation.deriveSecretAndBlindingFactor(
                    masterKey,
                    TEST_KEYSET_ID,
                    counter
            );

            DeterministicSecret secret = DeterministicSecret.create(
                    pair.secret(),
                    keysetId,
                    counter
            );

            BlindedMessage bm = BlindedMessage.builder()
                    .amount(8)
                    .keySetId(keysetId)
                    .blindedMessage(deriveTestPublicKey(masterKey, counter))
                    .build();

            // Mint this token
            PostMintRequest<Secret> mintRequest = new PostMintRequest<>(
                    UUID.randomUUID().toString(),
                    List.of(bm),
                    List.of(secret),
                    List.of(pair.blindingFactor())
            );

            NUT04.mint(UUID.randomUUID(), mintRequest, PaymentMethod.MOCK,
                    null, mintLoadService, protocolService, vault);

            mintedMessages.add(bm);
        }

        // Act: Try to recover all counters 0-4 (including gaps)
        List<BlindedMessage> allMessages = new ArrayList<>();
        for (int counter = 0; counter < 5; counter++) {
            BlindedMessage bm = BlindedMessage.builder()
                    .amount(8)
                    .keySetId(keysetId)
                    .blindedMessage(deriveTestPublicKey(masterKey, counter))
                    .build();
            allMessages.add(bm);
        }

        PostRestoreRequest restoreRequest = new PostRestoreRequest(allMessages);
        PostRestoreResponse restoreResponse = NUT09.restore(restoreRequest, vault);

        // Assert: Only the 3 minted tokens are recovered (gaps return empty)
        assertEquals(3, restoreResponse.getBlindSignatures().size(),
                "Should recover only the 3 minted tokens, not the gaps");

        // Verify the recovered messages are at the correct positions
        List<BlindedMessage> recovered = restoreResponse.getBlindedMessages();
        assertTrue(recovered.contains(allMessages.get(0)), "Counter 0 should be recovered");
        assertFalse(recovered.contains(allMessages.get(1)), "Counter 1 (gap) should not be recovered");
        assertTrue(recovered.contains(allMessages.get(2)), "Counter 2 should be recovered");
        assertFalse(recovered.contains(allMessages.get(3)), "Counter 3 (gap) should not be recovered");
        assertTrue(recovered.contains(allMessages.get(4)), "Counter 4 should be recovered");
    }

    /**
     * Tests NUT-13 recovery across multiple keysets. This simulates a user who has
     * received tokens from different mints or different keyset rotations. The recovery
     * should work independently for each keyset.
     */
    @Test
    @DisplayName("Should recover tokens from multiple keysets independently")
    void testRecoveryWithMultipleKeysets() throws CashuErrorException {
        // Arrange: Two different keysets
        String keysetId1Hex = "009a1f293253e41e";
        String keysetId2Hex = "00ad268c4d1f5826";
        KeysetId keysetId1 = KeysetId.fromString(keysetId1Hex);
        KeysetId keysetId2 = KeysetId.fromString(keysetId2Hex);

        DeterministicKey masterKey = Bip39.mnemonicToMasterKey(TEST_MNEMONIC, TEST_PASSPHRASE);

        // Mint 2 tokens from keyset1
        List<BlindedMessage> keyset1Messages = new ArrayList<>();
        for (int counter = 0; counter < 2; counter++) {
            var pair = Nut13Derivation.deriveSecretAndBlindingFactor(
                    masterKey,
                    keysetId1Hex,
                    counter
            );

            DeterministicSecret secret = DeterministicSecret.create(
                    pair.secret(),
                    keysetId1,
                    counter
            );

            BlindedMessage bm = BlindedMessage.builder()
                    .amount(8)
                    .keySetId(keysetId1)
                    .blindedMessage(deriveTestPublicKey(masterKey, counter))
                    .build();

            PostMintRequest<Secret> mintRequest = new PostMintRequest<>(
                    UUID.randomUUID().toString(),
                    List.of(bm),
                    List.of(secret),
                    List.of(pair.blindingFactor())
            );

            NUT04.mint(UUID.randomUUID(), mintRequest, PaymentMethod.MOCK,
                    null, mintLoadService, protocolService, vault);

            keyset1Messages.add(bm);
        }

        // Mint 2 tokens from keyset2
        List<BlindedMessage> keyset2Messages = new ArrayList<>();
        for (int counter = 0; counter < 2; counter++) {
            var pair = Nut13Derivation.deriveSecretAndBlindingFactor(
                    masterKey,
                    keysetId2Hex,
                    counter
            );

            DeterministicSecret secret = DeterministicSecret.create(
                    pair.secret(),
                    keysetId2,
                    counter
            );

            BlindedMessage bm = BlindedMessage.builder()
                    .amount(16)
                    .keySetId(keysetId2)
                    .blindedMessage(deriveTestPublicKey(masterKey, counter + SECONDARY_KEY_OFFSET)) // Different keys
                    .build();

            PostMintRequest<Secret> mintRequest = new PostMintRequest<>(
                    UUID.randomUUID().toString(),
                    List.of(bm),
                    List.of(secret),
                    List.of(pair.blindingFactor())
            );

            NUT04.mint(UUID.randomUUID(), mintRequest, PaymentMethod.MOCK,
                    null, mintLoadService, protocolService, vault);

            keyset2Messages.add(bm);
        }

        // Act: Recover from keyset1
        PostRestoreRequest restore1 = new PostRestoreRequest(keyset1Messages);
        PostRestoreResponse response1 = NUT09.restore(restore1, vault);

        // Act: Recover from keyset2
        PostRestoreRequest restore2 = new PostRestoreRequest(keyset2Messages);
        PostRestoreResponse response2 = NUT09.restore(restore2, vault);

        // Assert: Each keyset recovered independently
        assertEquals(2, response1.getBlindSignatures().size(),
                "Should recover 2 signatures from keyset1");
        assertEquals(2, response2.getBlindSignatures().size(),
                "Should recover 2 signatures from keyset2");

        // Verify keysets are correct
        response1.getBlindSignatures().forEach(sig ->
                assertEquals(keysetId1, sig.getKeySetId(),
                        "Keyset1 signatures should have correct keyset ID"));

        response2.getBlindSignatures().forEach(sig ->
                assertEquals(keysetId2, sig.getKeySetId(),
                        "Keyset2 signatures should have correct keyset ID"));
    }

    /**
     * Tests NUT-13 recovery when some tokens have already been spent. The recovery
     * process should still return the signatures, but the wallet should verify
     * spent status using NUT-07 separately (not tested here).
     */
    @Test
    @DisplayName("Should recover signatures even for spent tokens")
    void testRecoveryWithSpentTokens() throws CashuErrorException {
        // Arrange: Mint 3 tokens
        DeterministicKey masterKey = Bip39.mnemonicToMasterKey(TEST_MNEMONIC, TEST_PASSPHRASE);
        KeysetId keysetId = KeysetId.fromString(TEST_KEYSET_ID);

        List<BlindedMessage> messages = new ArrayList<>();
        for (int counter = 0; counter < 3; counter++) {
            var pair = Nut13Derivation.deriveSecretAndBlindingFactor(
                    masterKey,
                    TEST_KEYSET_ID,
                    counter
            );

            DeterministicSecret secret = DeterministicSecret.create(
                    pair.secret(),
                    keysetId,
                    counter
            );

            BlindedMessage bm = BlindedMessage.builder()
                    .amount(8)
                    .keySetId(keysetId)
                    .blindedMessage(deriveTestPublicKey(masterKey, counter))
                    .build();

            PostMintRequest<Secret> mintRequest = new PostMintRequest<>(
                    UUID.randomUUID().toString(),
                    List.of(bm),
                    List.of(secret),
                    List.of(pair.blindingFactor())
            );

            NUT04.mint(UUID.randomUUID(), mintRequest, PaymentMethod.MOCK,
                    null, mintLoadService, protocolService, vault);

            messages.add(bm);
        }

        // Note: In a real scenario, tokens would be marked as spent in a separate proof vault
        // The signature vault doesn't track spent status - that's handled by NUT-07

        // Act: Recover all tokens (including "spent" ones)
        PostRestoreRequest restoreRequest = new PostRestoreRequest(messages);
        PostRestoreResponse restoreResponse = NUT09.restore(restoreRequest, vault);

        // Assert: All signatures are recovered (spent status is checked separately via NUT-07)
        assertEquals(3, restoreResponse.getBlindSignatures().size(),
                "Should recover all signatures regardless of spent status");
        assertEquals(3, restoreResponse.getBlindedMessages().size(),
                "Should return all blinded messages");

        // The wallet would then use NUT-07 to check which proofs are still valid
        // That verification is outside the scope of signature recovery
    }

    /**
     * Tests deterministic secret reproducibility - the same mnemonic should always
     * produce the same secrets. This is the core property that enables NUT-13 recovery.
     */
    @Test
    @DisplayName("Should produce identical secrets from same mnemonic (reproducibility)")
    void testDeterministicReproducibility() {
        // Arrange
        String mnemonic = TEST_MNEMONIC;
        String keysetIdHex = TEST_KEYSET_ID;
        int counter = 0;

        // Act: Derive secret twice from same mnemonic
        var params = Nut13Derivation.Nut13DerivationParams.builder()
                .mnemonicPhrase(mnemonic)
                .passphrase(TEST_PASSPHRASE)
                .keysetIdHex(keysetIdHex)
                .counter(counter)
                .build();

        byte[] secret1 = Nut13Derivation.deriveSecretFromMnemonic(params);
        byte[] secret2 = Nut13Derivation.deriveSecretFromMnemonic(params);
        byte[] blindingFactor1 = Nut13Derivation.deriveBlindingFactorFromMnemonic(params);
        byte[] blindingFactor2 = Nut13Derivation.deriveBlindingFactorFromMnemonic(params);

        // Assert: Identical outputs
        assertArrayEquals(secret1, secret2,
                "Same mnemonic should produce identical secrets");
        assertArrayEquals(blindingFactor1, blindingFactor2,
                "Same mnemonic should produce identical blinding factors");

        // Verify they're not empty
        assertEquals(32, secret1.length, "Secret should be 32 bytes");
        assertEquals(32, blindingFactor1.length, "Blinding factor should be 32 bytes");
    }
}
