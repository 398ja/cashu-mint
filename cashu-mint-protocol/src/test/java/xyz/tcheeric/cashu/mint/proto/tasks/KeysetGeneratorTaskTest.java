package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;
import xyz.tcheeric.cashu.vault.api.db.impl.DBKeySetVault;
import xyz.tcheeric.cashu.vault.api.db.impl.DBKeyVault;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;

public class KeysetGeneratorTaskTest {

    @Test
    public void executeSuccess() throws CashuErrorException {
        KeySetEntity entity = new KeySetEntity();
        Keys keys = new Keys();

        try (MockedStatic<DBKeySetVault> ksv = Mockito.mockStatic(DBKeySetVault.class);
             MockedStatic<DBKeyVault> kv = Mockito.mockStatic(DBKeyVault.class)) {

            DBKeySetVault vault = Mockito.mock(DBKeySetVault.class);
            ksv.when(() -> DBKeySetVault.retrieveKeySet("mint", "sat")).thenReturn(vault);
            Mockito.when(vault.getEntity()).thenReturn(entity);
            kv.when(() -> DBKeyVault.load(entity)).thenReturn(keys);

            KeysetGeneratorTask task = new KeysetGeneratorTask("mint", "sat");
            KeySet result = task.execute();

            assertEquals("sat", result.getUnit());
            assertEquals(keys, result.getKeys());
            assertNotNull(result.getId());
        }
    }

    @Test
    public void executeFailure() {
        KeySetEntity entity = new KeySetEntity();

        try (MockedStatic<DBKeySetVault> ksv = Mockito.mockStatic(DBKeySetVault.class);
             MockedStatic<DBKeyVault> kv = Mockito.mockStatic(DBKeyVault.class)) {

            DBKeySetVault vault = Mockito.mock(DBKeySetVault.class);
            ksv.when(() -> DBKeySetVault.retrieveKeySet("mint", "sat")).thenReturn(vault);
            Mockito.when(vault.getEntity()).thenReturn(entity);
            kv.when(() -> DBKeyVault.load(entity)).thenThrow(new CashuErrorException("fail"));

            KeysetGeneratorTask task = new KeysetGeneratorTask("mint", "sat");
            assertThrows(CashuErrorException.class, task::execute);
        }
    }
}

