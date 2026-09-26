package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut12.DLEQProof;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.jpa.entity.BlindSignatureEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.BlindSignatureJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.SignatureSource;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

/**
 * Durable {@link SignatureVaultService} backed by the {@code blind_signature} table (issue #491).
 *
 * <p>Every mint instance on the same database shares this record, and it survives restarts, so
 * an output signed once is refused everywhere afterwards and NUT-09 restore can always return
 * what was issued.
 *
 * <p>{@link #store} is a plain {@code INSERT} in its own transaction. The primary key on
 * {@code b_} is the arbiter: of two requests racing to sign the same blinded message, whether on
 * one instance or two, exactly one insert commits and the other is refused with
 * {@code outputs_already_signed}. Running the insert in a new transaction keeps that refusal from
 * poisoning any transaction the caller may hold, and means the row is committed before the
 * signature is returned to the wallet.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class JpaSignatureVaultService implements SignatureVaultService {

    private final BlindSignatureJpaRepository repository;
    private final TransactionTemplate insertTransaction;

    public JpaSignatureVaultService(BlindSignatureJpaRepository repository,
                                    @Qualifier("mintTransactionManager") PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.insertTransaction = new TransactionTemplate(transactionManager);
        this.insertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void store(BlindedMessage message, BlindSignature signature, SignatureSource source)
            throws CashuErrorException {
        requireStorable(message, signature, source);
        BlindSignatureEntity row = toEntity(message, signature, source);
        try {
            insertTransaction.executeWithoutResult(status -> repository.saveAndFlush(row));
        } catch (DataIntegrityViolationException conflict) {
            throw refusal(row, conflict);
        }
    }

    @Override
    public BlindSignature retrieve(BlindedMessage message) throws CashuErrorException {
        if (message == null || message.getBlindedMessage() == null) {
            throw new CashuErrorException(CashuErrorCode.internal_error,
                    "Signature retrieval called with invalid arguments");
        }
        return repository.findById(message.getBlindedMessage().toString())
                .map(JpaSignatureVaultService::toBlindSignature)
                .orElse(null);
    }

    @Override
    public boolean isDurable() {
        return true;
    }

    private static void requireStorable(BlindedMessage message, BlindSignature signature, SignatureSource source)
            throws CashuErrorException {
        if (message == null || message.getBlindedMessage() == null || signature == null
                || signature.getBlindedSignature() == null || signature.getKeySetId() == null || source == null) {
            throw new CashuErrorException(CashuErrorCode.internal_error,
                    "Signature store called with invalid arguments");
        }
    }

    /**
     * Tells an already-signed output apart from any other constraint failure.
     *
     * <p>Only a row already present under the same {@code b_} is a duplicate. Anything else, such
     * as a malformed value tripping a check constraint, is a fault in the mint and must not be
     * reported to the wallet as if the output had been signed.
     */
    private CashuErrorException refusal(BlindSignatureEntity row, DataIntegrityViolationException conflict) {
        if (repository.existsById(row.getBlindedMessage())) {
            log.warn("signature_vault outputs_already_signed source={} keyset={}",
                    row.getSource(), row.getKeysetId());
            return new CashuErrorException(CashuErrorCode.outputs_already_signed);
        }
        log.error("signature_vault insert_failed source={} keyset={}", row.getSource(), row.getKeysetId(), conflict);
        return new CashuErrorException(CashuErrorCode.internal_error,
                "Failed to record the blind signature", conflict);
    }

    private static BlindSignatureEntity toEntity(BlindedMessage message, BlindSignature signature,
                                                 SignatureSource source) {
        BlindSignatureEntity row = new BlindSignatureEntity();
        row.setBlindedMessage(message.getBlindedMessage().toString());
        row.setKeysetId(signature.getKeySetId().toString());
        row.setAmount(signature.getAmount());
        row.setBlindSignature(signature.getBlindedSignature().toString());
        row.setSource(source);
        DLEQProof dleq = signature.getDleq();
        if (dleq != null) {
            row.setDleqE(dleq.getE());
            row.setDleqS(dleq.getS());
        }
        return row;
    }

    private static BlindSignature toBlindSignature(BlindSignatureEntity row) {
        DLEQProof dleq = row.getDleqE() == null
                ? null
                : DLEQProof.forBlindSignature(row.getDleqE(), row.getDleqS());
        return new BlindSignature(
                Math.toIntExact(row.getAmount()),
                KeysetId.fromString(row.getKeysetId()),
                Signature.fromString(row.getBlindSignature()),
                dleq);
    }
}
