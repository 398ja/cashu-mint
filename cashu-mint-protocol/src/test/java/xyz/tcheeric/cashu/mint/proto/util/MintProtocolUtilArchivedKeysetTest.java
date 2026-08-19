package xyz.tcheeric.cashu.mint.proto.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.KeyVault;
import xyz.tcheeric.cashu.vault.api.VaultClientFactory;
import xyz.tcheeric.cashu.vault.db.client.KeySetVaultClient;
import xyz.tcheeric.cashu.vault.db.model.KeyEntity;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;

/**
 * An archived keyset must never yield a signing key.
 *
 * <p>Archiving is how a keyset is retired — cashu-mint-admin archives a mint's
 * keysets when an operator retires it. Before this guard existed the signing
 * path resolved the private key by whatever keyset id the client supplied, so a
 * retired mint carried on issuing. See ADR-0004.
 */
class MintProtocolUtilArchivedKeysetTest {

    private static final String KEYSET_ID = "00ad268c4d1f5826";
    private static final String PRIVATE_KEY_HEX =
        "0000000000000000000000000000000000000000000000000000000000000001";

    // An archived keyset is refused with a distinct, wallet-actionable code.
    @Test
    void shouldRefuseToResolveKeyForArchivedKeyset() {
        final KeySetEntity archived = keySetEntity(true);

        try (MockedStatic<VaultClientFactory> factory = Mockito.mockStatic(VaultClientFactory.class)) {
            final KeySetVaultClient keySetClient = Mockito.mock(KeySetVaultClient.class);
            Mockito.when(keySetClient.getByKeySetId(KEYSET_ID)).thenReturn(archived);
            factory.when(VaultClientFactory::keySetClient).thenReturn(keySetClient);

            final CashuErrorException thrown = assertThrows(CashuErrorException.class,
                () -> MintProtocolUtil.getPrivateKeyForSigning(KEYSET_ID, 1, new Mint()));

            assertTrue(thrown.getMessage().contains("keyset_inactive"),
                "expected a keyset_inactive error but was: " + thrown.getMessage());
        }
    }

    // An active keyset passes the signing check.
    @Test
    void shouldAllowSigningWithActiveKeyset() throws CashuErrorException {
        final KeySetEntity active = keySetEntity(false);
        final KeyEntity keyEntity = new KeyEntity();
        keyEntity.setPrivateKey(PRIVATE_KEY_HEX);

        try (MockedStatic<VaultClientFactory> factory = Mockito.mockStatic(VaultClientFactory.class)) {
            final KeySetVaultClient keySetClient = Mockito.mock(KeySetVaultClient.class);
            Mockito.when(keySetClient.getByKeySetId(KEYSET_ID)).thenReturn(active);
            final KeyVault keyVault = Mockito.mock(KeyVault.class);
            Mockito.when(keyVault.retrieveByAmount(BigInteger.valueOf(1), active.getId().toString()))
                .thenReturn(keyEntity);
            factory.when(VaultClientFactory::keySetClient).thenReturn(keySetClient);
            factory.when(VaultClientFactory::keyVault).thenReturn(keyVault);

            assertNotNull(MintProtocolUtil.getPrivateKeyForSigning(KEYSET_ID, 1, new Mint()));
        }
    }

    // The asymmetry that matters: archiving retires a keyset for issuance only.
    // Melt and swap verification resolve the private key for an existing proof
    // through getPrivateKey, and must keep working for an archived keyset —
    // otherwise retiring a mint would strand every token it ever signed.
    @Test
    void shouldStillResolveKeyForArchivedKeysetWhenRedeeming() throws CashuErrorException {
        final KeySetEntity archived = keySetEntity(true);
        final KeyEntity keyEntity = new KeyEntity();
        keyEntity.setPrivateKey(PRIVATE_KEY_HEX);

        try (MockedStatic<VaultClientFactory> factory = Mockito.mockStatic(VaultClientFactory.class)) {
            final KeySetVaultClient keySetClient = Mockito.mock(KeySetVaultClient.class);
            Mockito.when(keySetClient.getByKeySetId(KEYSET_ID)).thenReturn(archived);
            final KeyVault keyVault = Mockito.mock(KeyVault.class);
            Mockito.when(keyVault.retrieveByAmount(BigInteger.valueOf(1), archived.getId().toString()))
                .thenReturn(keyEntity);
            factory.when(VaultClientFactory::keySetClient).thenReturn(keySetClient);
            factory.when(VaultClientFactory::keyVault).thenReturn(keyVault);

            assertNotNull(MintProtocolUtil.getPrivateKey(KEYSET_ID, 1, new Mint()));
        }
    }

    // An active keyset still resolves for signing.
    @Test
    void shouldResolveKeyForActiveKeyset() throws CashuErrorException {
        final KeySetEntity active = keySetEntity(false);
        final KeyEntity keyEntity = new KeyEntity();
        keyEntity.setPrivateKey(PRIVATE_KEY_HEX);

        try (MockedStatic<VaultClientFactory> factory = Mockito.mockStatic(VaultClientFactory.class)) {
            final KeySetVaultClient keySetClient = Mockito.mock(KeySetVaultClient.class);
            Mockito.when(keySetClient.getByKeySetId(KEYSET_ID)).thenReturn(active);
            final KeyVault keyVault = Mockito.mock(KeyVault.class);
            Mockito.when(keyVault.retrieveByAmount(BigInteger.valueOf(1), active.getId().toString()))
                .thenReturn(keyEntity);
            factory.when(VaultClientFactory::keySetClient).thenReturn(keySetClient);
            factory.when(VaultClientFactory::keyVault).thenReturn(keyVault);

            assertNotNull(MintProtocolUtil.getPrivateKey(KEYSET_ID, 1, new Mint()));
        }
    }

    // An unknown keyset stays distinguishable from an archived one, because a
    // wallet's recovery differs: refresh and retry versus give up.
    @Test
    void shouldDistinguishUnknownKeysetFromArchivedKeyset() {
        try (MockedStatic<VaultClientFactory> factory = Mockito.mockStatic(VaultClientFactory.class)) {
            final KeySetVaultClient keySetClient = Mockito.mock(KeySetVaultClient.class);
            Mockito.when(keySetClient.getByKeySetId(KEYSET_ID)).thenReturn(null);
            factory.when(VaultClientFactory::keySetClient).thenReturn(keySetClient);

            final CashuErrorException thrown = assertThrows(CashuErrorException.class,
                () -> MintProtocolUtil.getPrivateKeyForSigning(KEYSET_ID, 1, new Mint()));

            // Typed like keyset_inactive, so a wallet can tell "refresh and retry"
            // from "this mint has never had that keyset".
            assertTrue(thrown.getMessage().contains("keyset_not_found"),
                "expected a keyset_not_found error but was: " + thrown.getMessage());
            assertFalse(thrown.getMessage().contains("keyset_inactive"));
        }
    }

    private static KeySetEntity keySetEntity(final boolean archived) {
        final KeySetEntity entity = new KeySetEntity();
        entity.setId(UUID.randomUUID());
        entity.setKeySetId(KEYSET_ID);
        entity.setUnit("sat");
        entity.setArchived(archived);
        return entity;
    }
}
