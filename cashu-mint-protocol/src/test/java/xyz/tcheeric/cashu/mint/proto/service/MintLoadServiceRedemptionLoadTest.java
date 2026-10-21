package xyz.tcheeric.cashu.mint.proto.service;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * cashu-mint#548: the load swap and melt share. It models the vault-backed loader's contract, where
 * {@code load(id, archive)} throws unless the MINT's archived flag equals {@code archive}.
 */
class MintLoadServiceRedemptionLoadTest {

    private static final UUID ACTIVE_MINT = UUID.randomUUID();
    private static final UUID ARCHIVED_MINT = UUID.randomUUID();

    /** DBMintVault semantics: the mint flag must match, and each generation lists separately. */
    private static final MintLoadService VAULT_LIKE = new MintLoadService() {
        @Override
        public Mint load(UUID mintId, boolean archive) throws CashuErrorException {
            boolean mintArchived = mintId.equals(ARCHIVED_MINT);
            if (mintArchived != archive) {
                throw new CashuErrorException("Mint with ID " + mintId + " not found or not archived");
            }
            return mint(mintId, archive);
        }

        @Override
        public List<Mint> load(boolean archive) {
            return List.of(mint(ACTIVE_MINT, archive), mint(ARCHIVED_MINT, archive));
        }

        private Mint mint(UUID id, boolean archive) {
            Mint mint = new Mint(id.toString());
            mint.addKeySet(KeySet.builder().id((archive ? "old-" : "new-") + id).unit("sat").build());
            return mint;
        }
    };

    @Test
    void loadsAnActiveMint() throws CashuErrorException {
        Mint mint = MintLoadService.loadForRedemption(VAULT_LIKE, ACTIVE_MINT);

        assertEquals(ACTIVE_MINT.toString(), mint.getId());
        Set<String> ids = mint.getKeySets().stream().map(KeySet::getId).collect(Collectors.toSet());
        assertEquals(Set.of("new-" + ACTIVE_MINT), ids);
    }

    @Test
    void refusesAMintThatIsItselfArchived() {
        assertThrows(CashuErrorException.class,
                () -> MintLoadService.loadForRedemption(VAULT_LIKE, ARCHIVED_MINT));
    }
}
