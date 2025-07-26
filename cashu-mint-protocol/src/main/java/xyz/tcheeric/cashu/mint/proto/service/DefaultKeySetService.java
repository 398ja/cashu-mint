package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;

public class DefaultKeySetService implements KeySetService {
    @Override
    public KeySet getKeySet(String keySetId) throws CashuErrorException {
        return NUT02.keys(keySetId);
    }
}
