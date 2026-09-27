package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import xyz.tcheeric.cashu.mint.jpa.entity.SwapResponseCacheEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.SwapResponseCacheJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.CachedSwapResponse;
import xyz.tcheeric.cashu.mint.proto.ports.SwapResponseCache;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties;
import xyz.tcheeric.cashu.mint.proto.util.SwapRequestFingerprint;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Durable {@link SwapResponseCache} backed by the {@code swap_response_cache} table (issue #482).
 *
 * <p>Every instance on the same database shares it, and it survives restarts, so a wallet whose
 * swap was answered by one replica can replay it against another, or after a deploy.
 *
 * <p>The ttl is read from {@link MintCapabilityProperties}, the object {@code /v1/info} advertises
 * the NUT-19 {@code ttl} from, so the advertised lifetime and the enforced one are one number.
 *
 * <p>{@link #store} runs in its own transaction so that a failure here cannot roll back anything
 * the caller holds, and so that the entry is committed before the response reaches the wallet: a
 * wallet that sees the response and immediately replays must find it.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class SwapResponseCacheAdapter implements SwapResponseCache {

    private final SwapResponseCacheJpaRepository repository;
    private final TransactionTemplate insertTransaction;
    private final MintCapabilityProperties capabilities;
    private final Clock clock;

    @Autowired
    public SwapResponseCacheAdapter(SwapResponseCacheJpaRepository repository,
                                    @Qualifier("mintTransactionManager") PlatformTransactionManager transactionManager,
                                    MintCapabilityProperties capabilities) {
        this(repository, transactionManager, capabilities, Clock.systemUTC());
    }

    SwapResponseCacheAdapter(SwapResponseCacheJpaRepository repository,
                             PlatformTransactionManager transactionManager,
                             MintCapabilityProperties capabilities,
                             Clock clock) {
        this.repository = repository;
        this.insertTransaction = new TransactionTemplate(transactionManager);
        this.insertTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.capabilities = capabilities;
        this.clock = clock;
    }

    @Override
    public Optional<CachedSwapResponse> find(SwapRequestFingerprint fingerprint) {
        return repository.findUnexpired(fingerprint.hex(), clock.instant())
                .map(CachedSwapResponse.class::cast);
    }

    /**
     * Inserts the response, keeping whichever entry got there first.
     *
     * <p>A second insert for one fingerprint is possible only when two instances processed the
     * same request at once. The input hold and the durable signature vault let exactly one of
     * them sign, so the loser never reaches this call with a response of its own: a conflict here
     * can only be a duplicate of an equal response, and is ignored.
     */
    @Override
    public void store(SwapRequestFingerprint fingerprint, String responseJson) {
        SwapResponseCacheEntity row = toEntity(fingerprint, responseJson);
        try {
            insertTransaction.executeWithoutResult(status -> repository.saveAndFlush(row));
        } catch (DataIntegrityViolationException conflict) {
            if (!repository.existsById(fingerprint.hex())) {
                throw conflict;
            }
            log.info("[swap][nut19] cache_entry_already_present request_fingerprint={}", fingerprint);
        }
    }

    private SwapResponseCacheEntity toEntity(SwapRequestFingerprint fingerprint, String responseJson) {
        Instant now = clock.instant();
        SwapResponseCacheEntity row = new SwapResponseCacheEntity();
        row.setRequestFingerprint(fingerprint.hex());
        row.setResponseJson(responseJson);
        row.setCreatedAt(now);
        row.setExpiresAt(now.plus(capabilities.getCachedResponseTtl()));
        return row;
    }
}
