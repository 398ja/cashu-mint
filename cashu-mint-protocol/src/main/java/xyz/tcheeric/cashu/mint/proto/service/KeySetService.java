package xyz.tcheeric.cashu.mint.proto.service;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

public interface KeySetService {
    KeySet getKeySet(@NonNull String keySetId) throws CashuErrorException;
}
