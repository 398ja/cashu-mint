package xyz.tcheeric.cashu.mint.admin.adapter.out.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The layout of a key's vault path.
 *
 * <p>It must match what the HashiCorp-backed vault derives for itself
 * ({@code keys/{mintId}/{keySetId}/{amount}}), because provisioning supplies the
 * value for the database-backed vault — where the column is NOT NULL and nothing
 * else fills it — and the HashiCorp backend overwrites it with its own. A row
 * written under one backend has to remain resolvable under the other.
 */
class VaultPathTest {

    private static final UUID MINT_ID = UUID.fromString("44444444-5555-6666-7777-888888888888");

    // Ensures the path matches the vault's own layout, component for component.
    @Test
    void shouldUseTheLayoutTheVaultDerives() {
        assertThat(VaultProvisioningAdapter.vaultPath(MINT_ID, "0001516b4fd562ea", 8))
            .isEqualTo("keys/44444444-5555-6666-7777-888888888888/0001516b4fd562ea/8");
    }

    // Ensures each denomination gets its own path, so keys never collide in the vault.
    @Test
    void shouldGiveEachDenominationItsOwnPath() {
        assertThat(VaultProvisioningAdapter.vaultPath(MINT_ID, "0001516b4fd562ea", 1))
            .isNotEqualTo(VaultProvisioningAdapter.vaultPath(MINT_ID, "0001516b4fd562ea", 2));
    }

    // Ensures a rotated keyset writes to a different path than the one it replaces,
    // so rotation never overwrites the retired keyset's secrets.
    @Test
    void shouldSeparateKeysetsSoRotationDoesNotOverwrite() {
        assertThat(VaultProvisioningAdapter.vaultPath(MINT_ID, "0001516b4fd562ea", 8))
            .isNotEqualTo(VaultProvisioningAdapter.vaultPath(MINT_ID, "00d0c62acbe7184f", 8));
    }
}
