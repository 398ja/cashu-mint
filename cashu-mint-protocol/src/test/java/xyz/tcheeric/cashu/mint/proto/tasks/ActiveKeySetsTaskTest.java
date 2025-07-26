package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ActiveKeySetsTaskTest {

    @Test
    public void executeSuccess() throws CashuErrorException {
        KeySet active = KeySet.builder().id("a1").unit("sat").build();
        KeySet inactive = KeySet.builder().id("b1").unit("sat").build();
        MintLoadService service = Mockito.mock(MintLoadService.class);
        Mockito.when(service.keySets(false)).thenReturn(List.of(active));
        Mockito.when(service.keySets(true)).thenReturn(List.of(inactive));

        ActiveKeySetsTask task = new ActiveKeySetsTask(service);
        List<ActiveKeySet> result = task.execute();

        assertEquals(2, result.size());
        assertEquals("a1", result.get(0).getId());
        assertTrue(result.get(0).isActive());
        assertEquals("b1", result.get(1).getId());
        assertTrue(!result.get(1).isActive());
    }
}
