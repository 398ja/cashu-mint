package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MeltSagaTransitionEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaJpaRepository;
import xyz.tcheeric.cashu.mint.jpa.repository.MeltSagaTransitionJpaRepository;
import xyz.tcheeric.cashu.mint.proto.domain.MeltSagaState;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSaga;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MeltSagaTransition;

import java.util.List;
import java.util.Optional;

/**
 * JPA-backed implementation of {@link MeltSagaRepository}. Gated on
 * {@code cashu.mint.jpa.enabled=true} (same flag as the spec-001 adapters).
 */
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class MeltSagaRepositoryAdapter implements MeltSagaRepository {

    private final MeltSagaJpaRepository sagas;
    private final MeltSagaTransitionJpaRepository transitions;

    @Override
    public Optional<MeltSaga> findById(String meltSagaId) {
        return sagas.findById(meltSagaId).map(e -> (MeltSaga) e);
    }

    @Override
    public Optional<MeltSaga> findByQuoteId(String quoteId) {
        return sagas.findByQuoteId(quoteId).map(e -> (MeltSaga) e);
    }

    @Override
    public MeltSaga save(MeltSaga saga) {
        MeltSagaEntity entity = (saga instanceof MeltSagaEntity existing)
                ? existing
                : toEntity(saga);
        return sagas.save(entity);
    }

    @Override
    public int casState(String meltSagaId, MeltSagaState expected, MeltSagaState target) {
        return sagas.casState(meltSagaId, expected, target);
    }

    @Override
    @Transactional("mintTransactionManager")
    public MeltSagaTransition recordTransition(String meltSagaId,
                                               MeltSagaState fromState,
                                               MeltSagaState toState,
                                               String reason,
                                               String actor) {
        int nextSeq = transitions.maxSeq(meltSagaId) + 1;
        MeltSagaTransitionEntity entry = new MeltSagaTransitionEntity();
        entry.setMeltSagaId(meltSagaId);
        entry.setSeq(nextSeq);
        entry.setFromState(fromState);
        entry.setToState(toState);
        entry.setReason(reason);
        entry.setActor(actor == null ? "system" : actor);
        return transitions.save(entry);
    }

    @Override
    public List<MeltSagaTransition> transitionsFor(String meltSagaId) {
        return transitions.findTimeline(meltSagaId).stream()
                .map(e -> (MeltSagaTransition) e)
                .toList();
    }

    @Override
    public List<MeltSaga> findByState(MeltSagaState state) {
        return sagas.findByCurrentState(state).stream()
                .map(e -> (MeltSaga) e)
                .toList();
    }

    @Override
    public void updateResponseCache(String meltSagaId, String responseJson) {
        if (responseJson == null) {
            return;
        }
        sagas.updateResponseCache(meltSagaId, responseJson);
    }

    private static MeltSagaEntity toEntity(MeltSaga s) {
        MeltSagaEntity e = new MeltSagaEntity();
        e.setMeltSagaId(s.meltSagaId());
        e.setQuoteId(s.quoteId());
        e.setInvoiceAmount(s.invoiceAmount());
        e.setExactFeeReserve(s.exactFeeReserve());
        e.setAssertedFeeReserve(s.assertedFeeReserve());
        e.setInputAmount(s.inputAmount());
        e.setProofCount(s.proofCount());
        e.setCurrentState(s.currentState());
        e.setPaymentHash(s.paymentHash());
        e.setProvider(s.provider());
        e.setProviderEventId(s.providerEventId());
        e.setPaymentOutcomeReason(s.paymentOutcomeReason());
        e.setMeltResponseCache(s.meltResponseCache());
        e.setChangeOutputsHash(s.changeOutputsHash());
        e.setChangeSignaturesJson(s.changeSignaturesJson());
        return e;
    }
}
