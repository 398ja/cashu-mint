package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.util.KeySetDerivation;
import xyz.tcheeric.cashu.vault.api.db.impl.DBKeySetVault;
import xyz.tcheeric.cashu.vault.api.db.impl.DBKeyVault;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;


@Slf4j
@RequiredArgsConstructor
public class KeysetGeneratorTask implements Task<KeySet> {

    private final String mintId;
    private final String unit;

    @Override
    public KeySet execute() throws CashuErrorException {
        log.info("execute()");

        Keys keys = getKeys();
        log.info("Keys: {}", keys);

        KeySet keySet = KeySet.builder().unit(unit).keys(keys).build();
        keySet.setId(KeySetDerivation.getId(keys.values()));
        return keySet;
    }

    private Keys getKeys() throws CashuErrorException {
        log.info("getKeys()");

        KeySetEntity keySetEntity = DBKeySetVault.retrieveKeySet(mintId, unit).getEntity();
        return DBKeyVault.load(keySetEntity);
    }
}
