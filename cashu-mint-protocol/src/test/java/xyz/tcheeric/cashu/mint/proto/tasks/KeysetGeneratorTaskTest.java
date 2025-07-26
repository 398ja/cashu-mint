package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.api.db.impl.DBKeySetVault;
import xyz.tcheeric.cashu.vault.api.db.impl.DBKeyVault;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class KeysetGeneratorTaskTest {

    /**
     *
     * Executes a test case to verify the successful execution of the `KeysetGeneratorTask`.
     *
     * **Description**
     * This test simulates the behavior of the `KeysetGeneratorTask` when it successfully retrieves a `KeySet` from the database. It uses mocked static methods and objects to simulate database interactions.
     * It ensures that the `KeysetGeneratorTask` correctly interacts with the database and returns the expected `KeySet` object when the operation is successful.
     *
     * **Parameters**
     * - None
     *
     * **Test Steps**
     * 1. Creates a `KeySetEntity` and `Keys` object to simulate database entities.
     * 2. Mocks the static methods of `DBKeySetVault` and `DBKeyVault`:
     *    - `DBKeySetVault.retrieveKeySet` returns a mocked `DBKeySetVault` object.
     *    - `DBKeyVault.load` returns the `Keys` object.
     * 3. Creates a `KeysetGeneratorTask` with the parameters `"mint"` and `"sat"`.
     * 4. Executes the task and retrieves the result.
     * 5. Asserts the following:
     *    - The `unit` of the result is `"sat"`.
     *    - The `keys` of the result match the mocked `Keys` object.
     *    - The `id` of the result is not null.
     *
     * **Assertions**
     * - `assertEquals("sat", result.getUnit())` - Verifies the unit of the result.
     * - `assertEquals(keys, result.getKeys())` - Verifies the keys of the result.
     * - `assertNotNull(result.getId())` - Verifies the ID of the result is not null.
     *
     * @throws CashuErrorException
     */
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
}

