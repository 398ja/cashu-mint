package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Keys;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.crypto.util.KeySetIdV2Derivation;
import xyz.tcheeric.cashu.vault.api.db.impl.DBKeySetVault;
import xyz.tcheeric.cashu.vault.api.db.impl.DBKeyVault;
import xyz.tcheeric.cashu.vault.db.model.KeySetEntity;


@Slf4j
@RequiredArgsConstructor
public class KeysetGeneratorTask extends InstrumentedTask<KeySet> {

    private final String mintId;
    private final String unit;

    @Override
    protected KeySet doExecute() throws CashuErrorException {
        log.info("execute()");

        Keys keys = getKeys();
        log.info("Keys: {}", keys);

        KeySet keySet = KeySet.builder().unit(unit).keys(keys).build();
        // NUT-02 v2: the id commits to the unit and fee as well as the keys, so a fee change is a
        // new keyset rather than the same id quietly charging something different.
        keySet.setId(KeySetIdV2Derivation.getId(
                keys.values(), unit, keySet.getPartPerThousand(), null));
        return keySet;
    }

    private Keys getKeys() throws CashuErrorException {
        log.info("getKeys()");

        KeySetEntity keySetEntity = new DBKeySetVault().retrieveByMintIdAndUnit(mintId, unit);
        return DBKeyVault.load(keySetEntity);
    }
}
