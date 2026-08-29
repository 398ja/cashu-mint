package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
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
            throw new CashuErrorException(CashuErrorCode.internal_error, "Signature store called with invalid arguments");
        }

        String key = message.getBlindedMessage().toString();

        // Double-mint detection: check if signature already exists for this blinded message
        BlindSignature existing = store.putIfAbsent(key, signature);
        if (existing != null) {
            // Signature already exists - this could indicate a double-mint attempt
            // or an idempotent retry. Log for monitoring but allow operation to continue
            // since the same blinded message should produce the same signature.
            log.warn("Duplicate signature storage detected: blinded_message_key={} " +
                            "existing_keyset={} existing_amount={} new_keyset={} new_amount={}",
                    key.substring(0, Math.min(key.length(), 16)) + "...",
                    existing.getKeySetId(), existing.getAmount(),
                    signature.getKeySetId(), signature.getAmount());

            // Compare core signature (C') only, excluding the DLEQ proof which is
            // non-deterministic by design (uses SecureRandom). Same blinded message + same
            // private key always produces the same C', but different DLEQ proofs (e, s).
            boolean coreSignatureMatches = existing.getAmount() == signature.getAmount()
                    && java.util.Objects.equals(existing.getKeySetId(), signature.getKeySetId())
                    && java.util.Objects.equals(existing.getBlindedSignature(), signature.getBlindedSignature());
            if (!coreSignatureMatches) {
                log.error("CRITICAL: Different signatures for same blinded message! " +
                                "This indicates a potential double-mint or cryptographic issue. " +
                                "key={}", key);
            }
            return; // Keep the original signature
        }

        log.debug("Signature stored: keyset={}, amount={}, vault_size={}",
                signature.getKeySetId(), signature.getAmount(), store.size());
    }

    @Override
    public BlindSignature retrieve(BlindedMessage message) throws CashuErrorException {
        if (message == null) {
            throw new CashuErrorException(CashuErrorCode.internal_error, "Signature retrieval called with invalid arguments");
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
