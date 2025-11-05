package xyz.tcheeric.cashu.mint.proto.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultSignatureVaultService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NUT-13 Compatibility Test for SignatureVaultService.
 *
 * <p>This test verifies that the existing SignatureVaultService implementation
 * is compatible with NUT-13 deterministic secret derivation without requiring
 * any code changes.
 *
 * <p><b>Key Insight:</b> The SignatureVaultService stores signatures using the
 * blinded message as the key. Since deterministic secrets (NUT-13) produce the
 * same blinded messages when using the same secret and blinding factor, the
 * existing implementation naturally supports wallet recovery.
 *
 * <p><b>How It Works:</b>
 * <ol>
 *   <li>Wallet derives secret from mnemonic using BIP32 path (NUT-13)</li>
 *   <li>Wallet creates blinded message from deterministic secret</li>
 *   <li>Mint stores signature keyed by blinded message (current implementation)</li>
 *   <li>During recovery, wallet derives same secret → same blinded message</li>
 *   <li>Mint retrieves stored signature for the blinded message</li>
 * </ol>
 *
 * <p><b>Test Strategy:</b>
 * This test simulates the recovery scenario by:
 * <ul>
 *   <li>Storing a signature with a specific blinded message</li>
 *   <li>Creating an "identical" blinded message (simulating deterministic derivation)</li>
 *   <li>Verifying the signature can be retrieved</li>
 * </ul>
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/13.md">NUT-13 Specification</a>
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/09.md">NUT-09 Restore Specification</a>
 */
@DisplayName("NUT-13 Compatibility Tests for SignatureVaultService")
class SignatureVaultNUT13CompatibilityTest {

    private SignatureVaultService vaultService;

    @BeforeEach
    void setUp() {
        vaultService = new DefaultSignatureVaultService();
    }

    /**
     * Tests that a signature can be retrieved using the same blinded message during wallet recovery.
     * This simulates NUT-13 deterministic recovery where the same mnemonic produces the same blinded message.
     */
    @Test
    @DisplayName("Should retrieve signature using identical blinded message (deterministic secret scenario)")
    void testDeterministicSecretRetrieval() throws Exception {
        // Arrange: Create a blinded message (simulating initial minting with deterministic secret)
        String blindedMessageHex = "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2";
        PublicKey blindedPublicKey = PublicKey.fromString(blindedMessageHex);
        KeysetId keysetId = KeysetId.fromString("009a1f293253e41e");

        BlindedMessage originalBlindedMessage = BlindedMessage.builder()
                .amount(8)
                .keySetId(keysetId)
                .blindedMessage(blindedPublicKey)
                .build();

        // Create a signature (from mint signing the blinded message)
        String signatureHex = "03c724d7e195ba762e2e3a9d294e5fd3f0f4b1f7e2c5d8a9b3c6e1f4a7d2e5c8b4";
        BlindSignature signature = BlindSignature.builder()
                .amount(8)
                .keySetId(keysetId)
                .blindedSignature(Signature.fromString(signatureHex))
                .build();

        // Store the signature
        vaultService.store(originalBlindedMessage, signature);

        // Act: Simulate wallet recovery - recreate the "same" blinded message
        // In real NUT-13 scenario, this would be derived from the same:
        // - Mnemonic phrase
        // - Derivation path (m/129372'/0'/{keyset_id_int}'/{counter}'/0)
        // - Blinding factor (m/129372'/0'/{keyset_id_int}'/{counter}'/1)
        PublicKey recoveredBlindedPublicKey = PublicKey.fromString(blindedMessageHex);
        BlindedMessage recoveredBlindedMessage = BlindedMessage.builder()
                .amount(8)
                .keySetId(keysetId)
                .blindedMessage(recoveredBlindedPublicKey)
                .build();

        // Retrieve using the "recovered" blinded message
        BlindSignature retrievedSignature = vaultService.retrieve(recoveredBlindedMessage);

        // Assert: Should retrieve the original signature
        assertNotNull(retrievedSignature,
                "Signature should be retrievable using deterministically derived blinded message");
        assertEquals(signature.getAmount(), retrievedSignature.getAmount(),
                "Retrieved signature amount should match");
        assertEquals(signature.getKeySetId(), retrievedSignature.getKeySetId(),
                "Retrieved signature keyset ID should match");
        assertEquals(signature.getBlindedSignature().toString(), retrievedSignature.getBlindedSignature().toString(),
                "Retrieved signature should match original");
    }

    /**
     * Tests that the vault can store and retrieve multiple signatures from different counter positions.
     * This simulates batch recovery where a wallet derives multiple secrets with incrementing counters.
     */
    @Test
    @DisplayName("Should handle multiple deterministic secrets with different counters")
    void testMultipleDeterministicSecretsWithDifferentCounters() throws Exception {
        // Arrange: Simulate minting tokens at counter positions 0, 1, 2
        KeysetId keysetId = KeysetId.fromString("009a1f293253e41e");

        // Counter 0 - different blinded message
        BlindedMessage msg0 = createBlindedMessage(
                "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2",
                keysetId, 8);
        BlindSignature sig0 = createSignature(
                "03c724d7e195ba762e2e3a9d294e5fd3f0f4b1f7e2c5d8a9b3c6e1f4a7d2e5c8b4",
                keysetId, 8);

        // Counter 1 - different blinded message
        BlindedMessage msg1 = createBlindedMessage(
                "03b5c9d2e3f4a7b8c9d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4",
                keysetId, 16);
        BlindSignature sig1 = createSignature(
                "02d8e3f5a9b2c4d6e8f0a1b3c5d7e9f1a2b4c6d8e0f2a4b6c8d0e2f4a6b8c0d2e4",
                keysetId, 16);

        // Counter 2 - different blinded message
        BlindedMessage msg2 = createBlindedMessage(
                "02c6d8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8",
                keysetId, 32);
        BlindSignature sig2 = createSignature(
                "03e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0f2a4b6c8d0e2f4a6b8c0d2e4f6",
                keysetId, 32);

        // Store all signatures
        vaultService.store(msg0, sig0);
        vaultService.store(msg1, sig1);
        vaultService.store(msg2, sig2);

        // Act & Assert: Retrieve each signature using "recovered" blinded messages
        BlindSignature retrieved0 = vaultService.retrieve(createBlindedMessage(
                "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2",
                keysetId, 8));
        assertNotNull(retrieved0, "Counter 0 signature should be retrievable");
        assertEquals(8, retrieved0.getAmount());

        BlindSignature retrieved1 = vaultService.retrieve(createBlindedMessage(
                "03b5c9d2e3f4a7b8c9d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4",
                keysetId, 16));
        assertNotNull(retrieved1, "Counter 1 signature should be retrievable");
        assertEquals(16, retrieved1.getAmount());

        BlindSignature retrieved2 = vaultService.retrieve(createBlindedMessage(
                "02c6d8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8",
                keysetId, 32));
        assertNotNull(retrieved2, "Counter 2 signature should be retrievable");
        assertEquals(32, retrieved2.getAmount());
    }

    /**
     * Tests that the vault returns null when a blinded message was never minted.
     * This simulates gap detection during NUT-13 recovery when a counter position was skipped.
     */
    @Test
    @DisplayName("Should return null for non-existent blinded message (gap in derivation)")
    void testNonExistentBlindedMessage() throws Exception {
        // Arrange: Create a blinded message that was never minted
        String unmintedBlindedMessageHex = "02ffffff0000000000000000000000000000000000000000000000000000000000";
        KeysetId keysetId = KeysetId.fromString("009a1f293253e41e");

        BlindedMessage unmintedMessage = BlindedMessage.builder()
                .amount(8)
                .keySetId(keysetId)
                .blindedMessage(PublicKey.fromString(unmintedBlindedMessageHex))
                .build();

        // Act: Try to retrieve signature for unminted token
        BlindSignature retrievedSignature = vaultService.retrieve(unmintedMessage);

        // Assert: Should return null (indicating gap in counter sequence)
        assertNull(retrievedSignature,
                "Should return null for blinded messages that were never minted (gap in counter sequence)");
    }

    /**
     * Tests that the vault can handle signatures from multiple different keysets independently.
     * This verifies that the storage mechanism correctly distinguishes between different keysets.
     */
    @Test
    @DisplayName("Should handle multiple keysets independently")
    void testMultipleKeysets() throws Exception {
        // Arrange: Different blinded messages for two different keysets
        // In reality, different keysets produce different blinded messages for same counter
        // because the blinding is performed against different public keys
        String blindedMessageHex1 = "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2";
        String blindedMessageHex2 = "03b8d9e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6e8f0a2b4c6d8e0f2a4b6c8d0";

        KeysetId keyset1 = KeysetId.fromString("009a1f293253e41e");
        KeysetId keyset2 = KeysetId.fromString("00ad268c4d1f5826");

        BlindedMessage msg1 = createBlindedMessage(blindedMessageHex1, keyset1, 8);
        BlindedMessage msg2 = createBlindedMessage(blindedMessageHex2, keyset2, 8);

        BlindSignature sig1 = createSignature(
                "03c724d7e195ba762e2e3a9d294e5fd3f0f4b1f7e2c5d8a9b3c6e1f4a7d2e5c8b4",
                keyset1, 8);
        BlindSignature sig2 = createSignature(
                "02d8e3f5a9b2c4d6e8f0a1b3c5d7e9f1a2b4c6d8e0f2a4b6c8d0e2f4a6b8c0d2e4",
                keyset2, 8);

        // Store both
        vaultService.store(msg1, sig1);
        vaultService.store(msg2, sig2);

        // Act & Assert: Retrieve both independently
        BlindSignature retrieved1 = vaultService.retrieve(msg1);
        BlindSignature retrieved2 = vaultService.retrieve(msg2);

        assertNotNull(retrieved1, "Keyset 1 signature should be retrievable");
        assertNotNull(retrieved2, "Keyset 2 signature should be retrievable");
        assertEquals(keyset1, retrieved1.getKeySetId());
        assertEquals(keyset2, retrieved2.getKeySetId());
    }

    /**
     * Tests that the vault uses only the blinded message as the storage key, not the original secret.
     * This demonstrates why NUT-13 recovery works: same secret produces same blinded message.
     */
    @Test
    @DisplayName("Should demonstrate storage key uses blinded message, not original secret")
    void testStorageKeyIsBlindedMessage() throws Exception {
        // This test demonstrates that the vault doesn't need to know about secrets
        // or derivation paths - it only cares about the blinded message

        KeysetId keysetId = KeysetId.fromString("009a1f293253e41e");
        String blindedMessageHex = "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2";

        // Create blinded message WITHOUT any reference to the secret
        BlindedMessage blindedMessage = BlindedMessage.builder()
                .amount(8)
                .keySetId(keysetId)
                .blindedMessage(PublicKey.fromString(blindedMessageHex))
                .build();

        BlindSignature signature = createSignature(
                "03c724d7e195ba762e2e3a9d294e5fd3f0f4b1f7e2c5d8a9b3c6e1f4a7d2e5c8b4",
                keysetId, 8);

        // Store
        vaultService.store(blindedMessage, signature);

        // Retrieve using ANY blinded message with the same hex value
        // This simulates recovery: same secret + same blinding factor = same blinded message
        BlindedMessage recoveredMessage = BlindedMessage.builder()
                .amount(8)
                .keySetId(keysetId)
                .blindedMessage(PublicKey.fromString(blindedMessageHex))
                .build();

        BlindSignature retrieved = vaultService.retrieve(recoveredMessage);

        assertNotNull(retrieved,
                "Vault should retrieve signature based solely on blinded message value, " +
                "regardless of how the secret was derived (random or deterministic)");
    }

    /**
     * Tests that two different BlindedMessage objects with the same data produce the same storage key.
     * This verifies that object equality is based on byte content, enabling successful recovery.
     */
    @Test
    @DisplayName("Should verify blinded message equality is based on bytes, not object identity")
    void testBlindedMessageEqualitySemantics() throws Exception {
        // This test verifies that the storage key (blinded message toString())
        // works correctly for object equality

        String blindedMessageHex = "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2";
        KeysetId keysetId = KeysetId.fromString("009a1f293253e41e");

        // Create two separate BlindedMessage objects with same data
        BlindedMessage msg1 = createBlindedMessage(blindedMessageHex, keysetId, 8);
        BlindedMessage msg2 = createBlindedMessage(blindedMessageHex, keysetId, 8);

        // Verify they are different objects
        assertNotSame(msg1, msg2, "Should be different object instances");

        // But their storage keys should be identical
        String key1 = msg1.getBlindedMessage().toString();
        String key2 = msg2.getBlindedMessage().toString();

        assertEquals(key1, key2,
                "Blinded messages with same bytes should produce same storage key");

        // Verify this works in practice with the vault
        BlindSignature sig = createSignature(
                "03c724d7e195ba762e2e3a9d294e5fd3f0f4b1f7e2c5d8a9b3c6e1f4a7d2e5c8b4",
                keysetId, 8);

        vaultService.store(msg1, sig);
        BlindSignature retrieved = vaultService.retrieve(msg2);

        assertNotNull(retrieved,
                "Should retrieve signature using different BlindedMessage object with same data");
    }

    // Helper methods

    private BlindedMessage createBlindedMessage(String blindedMessageHex, KeysetId keysetId, int amount) {
        return BlindedMessage.builder()
                .amount(amount)
                .keySetId(keysetId)
                .blindedMessage(PublicKey.fromString(blindedMessageHex))
                .build();
    }

    private BlindSignature createSignature(String signatureHex, KeysetId keysetId, int amount) {
        return BlindSignature.builder()
                .amount(amount)
                .keySetId(keysetId)
                .blindedSignature(Signature.fromString(signatureHex))
                .build();
    }
}
