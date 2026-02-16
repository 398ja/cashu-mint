package xyz.tcheeric.cashu.mint.admin.application.service;

import java.time.Clock;

import xyz.tcheeric.cashu.mint.admin.application.port.in.MonitorMintHealthUseCase;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintHealthRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintHealthRepository.MintHealthSnapshot;
import xyz.tcheeric.cashu.mint.admin.application.port.out.MintHealthRepository.MintHealthStatus;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;

/**
 * Implements {@link MonitorMintHealthUseCase} for health snapshot queries.
 */
public class MonitorMintHealthInteractor extends AbstractUseCaseInteractor
    implements MonitorMintHealthUseCase {

    private final MintHealthRepository mintHealthRepository;
    private final Clock clock;

    public MonitorMintHealthInteractor(final MintHealthRepository mintHealthRepository, final Clock clock) {
        this.mintHealthRepository = java.util.Objects.requireNonNull(mintHealthRepository,
            "mint health repository must not be null");
        this.clock = java.util.Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public MonitorMintHealthResponse handle(final MonitorMintHealthRequest request) {
        final MonitorMintHealthRequest validated = requireRequest(request, "monitor mint health request");
        validateMintId(validated.mintId());
        if (validated.query() == null) {
            throw new IllegalArgumentException("health query must not be null");
        }
        validateVersionTag(validated.versionTag());

        return switch (validated.query()) {
            case SNAPSHOT -> snapshot(validated);
            case STREAM -> stream(validated);
            case ACKNOWLEDGE_ALERT -> acknowledgeAlert(validated);
        };
    }

    /**
     * Registers or updates a mint's health state. Used by lifecycle event listeners.
     */
    public void updateHealth(final String mintId, final HealthStatus status, final String lifecycleState) {
        final MintId validatedMintId = validateMintId(mintId);
        mintHealthRepository.upsert(new MintHealthSnapshot(validatedMintId,
            toPersistedStatus(status), lifecycleState, clock.instant()));
    }

    private MonitorMintHealthResponse snapshot(final MonitorMintHealthRequest request) {
        final MintId mintId = validateMintId(request.mintId());
        final MintHealthSnapshot record = mintHealthRepository.findByMintId(mintId).orElse(null);
        if (record == null) {
            return new MonitorMintHealthResponse(request.mintId(), HealthStatus.UNKNOWN,
                null, clock.instant(), request.versionTag(), "No health data available");
        }
        return new MonitorMintHealthResponse(request.mintId(), toUseCaseStatus(record.status()),
            record.lifecycleState(), record.checkedAt(), request.versionTag(), "Health snapshot retrieved");
    }

    private MonitorMintHealthResponse stream(final MonitorMintHealthRequest request) {
        // Streaming not supported in REST; return snapshot instead
        return snapshot(request);
    }

    private MonitorMintHealthResponse acknowledgeAlert(final MonitorMintHealthRequest request) {
        final MintId mintId = validateMintId(request.mintId());
        final MintHealthSnapshot record = mintHealthRepository.findByMintId(mintId).orElse(null);
        if (record == null) {
            throw new IllegalStateException("mint not found: " + request.mintId());
        }
        final MintHealthSnapshot acknowledged = new MintHealthSnapshot(
            mintId, MintHealthStatus.HEALTHY, record.lifecycleState(), clock.instant());
        mintHealthRepository.upsert(acknowledged);
        return new MonitorMintHealthResponse(request.mintId(), HealthStatus.HEALTHY,
            record.lifecycleState(), acknowledged.checkedAt(), request.versionTag(),
            "Alert acknowledged, health reset");
    }

    private MintHealthStatus toPersistedStatus(final HealthStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("health status must not be null");
        }
        return MintHealthStatus.valueOf(status.name());
    }

    private HealthStatus toUseCaseStatus(final MintHealthStatus status) {
        return HealthStatus.valueOf(status.name());
    }
}
