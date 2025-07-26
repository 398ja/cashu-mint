package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LoadKeySetTaskTest {

    @Test
    public void executeSuccess() throws CashuErrorException {
        KeySet ks = KeySet.builder().id("ks1").unit("sat").build();
        MintLoadService service = Mockito.mock(MintLoadService.class);
        Mockito.when(service.keySets()).thenReturn(List.of(ks));

        LoadKeySetTask task = new LoadKeySetTask("ks1", service);
        KeySet result = task.execute();

        assertEquals(ks, result);
    }
}
