package xyz.tcheeric.cashu.mint.proto.service.impl;

import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DefaultSignatureVaultService implements SignatureVaultService {

    private final Map<String, BlindSignature> store = new ConcurrentHashMap<>();

    @Override
    public void store(BlindedMessage message, BlindSignature signature) throws CashuErrorException {
        if (message == null || signature == null) {
            throw new CashuErrorException("store_invalid_arguments");
        }
        store.put(message.getBlindedMessage().toString(), signature);
    }

    @Override
    public BlindSignature retrieve(BlindedMessage message) throws CashuErrorException {
        if (message == null) {
            throw new CashuErrorException("retrieve_invalid_arguments");
        }
        return store.get(message.getBlindedMessage().toString());
    }
}
