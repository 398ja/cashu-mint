package xyz.tcheeric.cashu.mint.jpa.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut12.DLEQProof;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.jpa.entity.BlindSignatureEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.BlindSignatureJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.SignatureSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JpaSignatureVaultService}, the durable signature vault (issue #491).
 *
 * <p>The database behaviour itself, restarts and two instances sharing one table, is covered
 * against Postgres in {@code DurableSignatureVaultIT}. These pin the translation between the
 * protocol types and the row, and between a unique violation and the protocol error.
 */
class JpaSignatureVaultServiceTest {

    private static final String BLINDED_MESSAGE =
            "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2";
    private static final String C_ =
            "03c724d7e195ba762e2e3a9d294e5fd3f0f4b1f7e2c5d8a9b3c6e1f4a7d2e5c8b4";
    private static final String V2_KEYSET_ID =
            "01" + "ad268c4d1f5826c3a3ce9d1ad31f2bb7ebd5e2a6c9a1d4f7e2b3c4d5e6f70812";

    private BlindSignatureJpaRepository repository;
    private PlatformTransactionManager transactionManager;
    private JpaSignatureVaultService vault;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(BlindSignatureJpaRepository.class);
        transactionManager = Mockito.mock(PlatformTransactionManager.class);
        vault = new JpaSignatureVaultService(repository, transactionManager);
    }

    /** Storing writes one row carrying the blinded message, keyset, amount, C_ and source. */
    @Test
    void storeInsertsOneRowDescribingTheSignature() throws CashuErrorException {
        vault.store(message(), signature(null), SignatureSource.SWAP);

        BlindSignatureEntity row = savedRow();
        assertThat(row.getBlindedMessage()).isEqualTo(BLINDED_MESSAGE);
        assertThat(row.getKeysetId()).isEqualTo(V2_KEYSET_ID);
        assertThat(row.getAmount()).isEqualTo(8L);
        assertThat(row.getBlindSignature()).isEqualTo(C_);
        assertThat(row.getSource()).isEqualTo(SignatureSource.SWAP);
        assertThat(row.isNew()).as("a new row must be persisted, never merged over an existing one").isTrue();
    }

    /** The insert runs in its own transaction, so a refusal cannot roll back work the caller holds. */
    @Test
    void storeRunsInANewTransaction() throws CashuErrorException {
        vault.store(message(), signature(null), SignatureSource.MINT);

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A second signature on a blinded message that already has a row is refused as outputs_already_signed. */
    @Test
    void aDuplicateBlindedMessageIsRefusedAsOutputsAlreadySigned() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(repository.existsById(BLINDED_MESSAGE)).thenReturn(true);

        assertThatThrownBy(() -> vault.store(message(), signature(null), SignatureSource.MINT))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.outputs_already_signed);
    }

    /**
     * A constraint failure with no existing row is a fault in the mint, not a signed output, so it
     * must not tell the wallet its output was already signed.
     */
    @Test
    void anotherConstraintFailureIsAnInternalErrorNotADuplicate() {
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("check violated"));
        when(repository.existsById(BLINDED_MESSAGE)).thenReturn(false);

        assertThatThrownBy(() -> vault.store(message(), signature(null), SignatureSource.MINT))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.internal_error);
    }

    /** Store refuses to record a signature without its source, so every row says why it exists. */
    @Test
    void storeRequiresASource() {
        assertThatThrownBy(() -> vault.store(message(), signature(null), null))
                .isInstanceOf(CashuErrorException.class)
                .extracting(e -> ((CashuErrorException) e).getErrorCode())
                .isEqualTo(CashuErrorCode.internal_error);
        verify(repository, never()).saveAndFlush(any());
    }

    /** Retrieving a blinded message the vault has never seen returns null, as NUT-09 expects for a gap. */
    @Test
    void retrieveReturnsNullForAnUnknownBlindedMessage() throws CashuErrorException {
        when(repository.findById(BLINDED_MESSAGE)).thenReturn(Optional.empty());

        assertThat(vault.retrieve(message())).isNull();
    }

    /** A signature stored with a DLEQ proof comes back with the same C_, keyset, amount and proof. */
    @Test
    void aSignatureWithDleqRoundTripsUnchanged() throws CashuErrorException {
        DLEQProof dleq = DLEQProof.forBlindSignature("e".repeat(64), "f".repeat(64));
        vault.store(message(), signature(dleq), SignatureSource.MINT);
        BlindSignatureEntity row = savedRow();
        when(repository.findById(BLINDED_MESSAGE)).thenReturn(Optional.of(row));

        BlindSignature restored = vault.retrieve(message());

        assertThat(restored.getAmount()).isEqualTo(8);
        assertThat(restored.getKeySetId()).isEqualTo(KeysetId.fromString(V2_KEYSET_ID));
        assertThat(restored.getBlindedSignature()).isEqualTo(Signature.fromString(C_));
        assertThat(restored.getDleq().getE()).isEqualTo(dleq.getE());
        assertThat(restored.getDleq().getS()).isEqualTo(dleq.getS());
    }

    /** A signature stored without a DLEQ proof comes back without one rather than with an empty proof. */
    @Test
    void aSignatureWithoutDleqRoundTripsWithoutOne() throws CashuErrorException {
        vault.store(message(), signature(null), SignatureSource.MINT);
        BlindSignatureEntity row = savedRow();
        when(repository.findById(BLINDED_MESSAGE)).thenReturn(Optional.of(row));

        assertThat(vault.retrieve(message()).getDleq()).isNull();
    }

    /** The JPA vault reports itself durable, which is what lets a production profile boot. */
    @Test
    void reportsItselfAsDurable() {
        assertThat(vault.isDurable()).isTrue();
    }

    private BlindSignatureEntity savedRow() {
        ArgumentCaptor<BlindSignatureEntity> row = ArgumentCaptor.forClass(BlindSignatureEntity.class);
        verify(repository).saveAndFlush(row.capture());
        return row.getValue();
    }

    private static BlindedMessage message() {
        return BlindedMessage.builder()
                .amount(8)
                .keySetId(KeysetId.fromString(V2_KEYSET_ID))
                .blindedMessage(PublicKey.fromString(BLINDED_MESSAGE))
                .build();
    }

    private static BlindSignature signature(DLEQProof dleq) {
        return new BlindSignature(8, KeysetId.fromString(V2_KEYSET_ID), Signature.fromString(C_), dleq);
    }
}
