package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.nut.NUT01;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

public class LoadKeySetsTaskTest {

    @Test
    public void executeSuccess() throws CashuErrorException {
        UUID mintId = UUID.randomUUID();
        Mint mint = new Mint(mintId.toString());
        KeySet ks1 = KeySet.builder().id("ks1").unit("sat").build();
        KeySet ks2 = KeySet.builder().unit("sat").build();
        mint.addKeySet(ks1);
        mint.addKeySet(ks2);

        MintLoadService service = Mockito.mock(MintLoadService.class);
        when(service.load(mintId, false)).thenReturn(mint);

        KeySet generated = KeySet.builder().id("generated").unit("sat").build();
        try (MockedStatic<NUT01> nut01 = Mockito.mockStatic(NUT01.class)) {
            nut01.when(() -> NUT01.generateKeySet(mintId, "sat")).thenReturn(generated);

            LoadKeySetsTask task = new LoadKeySetsTask(mintId, service);
            List<KeySet> result = task.execute();

            assertEquals(2, result.size());
            assertNotNull(ks2.getId());
            assertEquals("generated", ks2.getId());
        }
    }
}
