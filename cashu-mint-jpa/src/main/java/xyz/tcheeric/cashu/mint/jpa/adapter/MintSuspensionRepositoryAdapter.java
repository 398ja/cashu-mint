package xyz.tcheeric.cashu.mint.jpa.adapter;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import xyz.tcheeric.cashu.mint.jpa.entity.MintSuspensionEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.MintSuspensionJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintSuspensionRepository;

import java.time.Clock;
import java.time.Instant;

/**
 * Durable backing for {@link MintSuspensionRepository}.
 */
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class MintSuspensionRepositoryAdapter implements MintSuspensionRepository {

    private final MintSuspensionJpaRepository repository;

    // A single constructor, and no injected Clock: this application defines no
    // Clock bean, and a second constructor leaves Spring unable to choose.
    private final Clock clock = Clock.systemUTC();

    public MintSuspensionRepositoryAdapter(final MintSuspensionJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isIssuanceSuspended(final String mintId) {
        return mintId != null && repository.existsById(mintId);
    }

    @Override
    @Transactional
    public void suspend(final String mintId, final String reason) {
        // Re-suspending an already suspended mint keeps the original timestamp,
        // so the record answers "since when" rather than "most recently asked".
        if (repository.existsById(mintId)) {
            return;
        }
        final MintSuspensionEntity entity = new MintSuspensionEntity();
        entity.setMintId(mintId);
        entity.setReason(reason);
        entity.setSuspendedAt(Instant.now(clock));
        repository.save(entity);
    }

    @Override
    @Transactional
    public void resume(final String mintId) {
        repository.deleteById(mintId);
    }
}
