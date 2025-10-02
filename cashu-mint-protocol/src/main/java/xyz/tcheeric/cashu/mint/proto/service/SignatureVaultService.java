package xyz.tcheeric.cashu.mint.proto.service;

import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

public interface SignatureVaultService {
    void store(BlindedMessage message, BlindSignature signature) throws CashuErrorException;
    BlindSignature retrieve(BlindedMessage message) throws CashuErrorException;
}
