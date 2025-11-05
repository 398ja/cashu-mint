package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory implementation of {@link SignatureVaultService}.
 *
 * <p>This implementation stores signatures in a {@link ConcurrentHashMap} for
 * thread-safe access. The storage is volatile - signatures are lost on restart.
 * Production deployments should consider a persistent implementation.
 *
 * <p><b>NUT-13 Compatibility:</b> This implementation is fully compatible with
 * NUT-13 deterministic recovery. Since deterministic secrets produce deterministic
 * blinded messages, the same key will be used during recovery, allowing successful
 * signature retrieval.
 *
 * @see SignatureVaultService
 */
@Slf4j
@Service
public class DefaultSignatureVaultService implements SignatureVaultService {

    private final Map<String, BlindSignature> store = new ConcurrentHashMap<>();

    @Override
    public void store(BlindedMessage message, BlindSignature signature) throws CashuErrorException {
        if (message == null || signature == null) {
            throw new CashuErrorException("store_invalid_arguments");
        }

        String key = message.getBlindedMessage().toString();
        store.put(key, signature);

        log.debug("Signature stored: keyset={}, amount={}, vault_size={}",
                signature.getKeySetId(), signature.getAmount(), store.size());
    }

    @Override
    public BlindSignature retrieve(BlindedMessage message) throws CashuErrorException {
        if (message == null) {
            throw new CashuErrorException("retrieve_invalid_arguments");
        }

        String key = message.getBlindedMessage().toString();
        BlindSignature signature = store.get(key);

        if (signature != null) {
            log.trace("Signature retrieved: keyset={}, amount={}",
                    signature.getKeySetId(), signature.getAmount());
        } else {
            log.trace("Signature not found for blinded message (possible gap in NUT-13 recovery)");
        }

        return signature;
    }
}
