package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.domain.SignatureSource;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link SignatureVaultService} for local development and tests.
 *
 * <p>The storage is volatile: every record is lost on restart and each process has its own
 * map. That is why it reports {@link #isDurable()} {@code false}, and why production profiles
 * refuse to boot with it (issue #491). The durable implementation lives in
 * {@code cashu-mint-jpa} and replaces this one whenever {@code cashu.mint.jpa.enabled=true}.
 *
 * <p>It is registered by {@link SignatureVaultFallbackAutoConfiguration} only when no other
 * vault is present, rather than by component scan, so the durable vault never has to win a
 * bean-ordering race against it.
 *
 * <p><b>NUT-13 Compatibility:</b> Since deterministic secrets produce deterministic blinded
 * messages, the same key is used during recovery, allowing successful signature retrieval.
 *
 * @see SignatureVaultService
 */
@Slf4j
public class DefaultSignatureVaultService implements SignatureVaultService {

    private final Map<String, BlindSignature> store = new ConcurrentHashMap<>();

    @Override
    public void store(BlindedMessage message, BlindSignature signature, SignatureSource source)
            throws CashuErrorException {
        if (message == null || signature == null || source == null) {
            throw new CashuErrorException(CashuErrorCode.internal_error, "Signature store called with invalid arguments");
        }

        String key = message.getBlindedMessage().toString();
        if (store.putIfAbsent(key, signature) != null) {
            log.warn("signature_vault outputs_already_signed source={} keyset={}", source, signature.getKeySetId());
            throw new CashuErrorException(CashuErrorCode.outputs_already_signed);
        }

        log.debug("Signature stored: source={} keyset={} amount={} vault_size={}",
                source, signature.getKeySetId(), signature.getAmount(), store.size());
    }

    @Override
    public BlindSignature retrieve(BlindedMessage message) throws CashuErrorException {
        if (message == null) {
            throw new CashuErrorException(CashuErrorCode.internal_error, "Signature retrieval called with invalid arguments");
        }

        BlindSignature signature = store.get(message.getBlindedMessage().toString());
        if (signature == null) {
            log.trace("Signature not found for blinded message (possible gap in NUT-13 recovery)");
        }
        return signature;
    }

    @Override
    public boolean isDurable() {
        return false;
    }
}
