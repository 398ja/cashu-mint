package xyz.tcheeric.cashu.mint.proto.util;

import org.mockito.Mockito;
import xyz.tcheeric.cashu.mint.proto.vault.ProofRepository;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

public final class TestVaultUtil {
    private TestVaultUtil() {}

    public static void mockRetrieveProof(ProofRepository repo, ProofEntity entity) throws CashuErrorException {
        Mockito.when(repo.retrieveProof(Mockito.anyString())).thenReturn(entity);
    }

    public static void mockStore(ProofRepository repo) throws CashuErrorException {
        Mockito.doNothing().when(repo).store(Mockito.any());
    }

    public static void mockInvalidate(ProofRepository repo) throws CashuErrorException {
        Mockito.doNothing().when(repo).invalidate(Mockito.any());
    }

    public static void mockArchive(ProofRepository repo) throws CashuErrorException {
        Mockito.doNothing().when(repo).archive(Mockito.any());
    }

    public static void mockStorePending(ProofRepository repo) throws CashuErrorException {
        Mockito.doNothing().when(repo).storePending(Mockito.any());
    }
}
