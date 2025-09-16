package xyz.tcheeric.cashu.mint.admin.cli.port.stub;

import xyz.tcheeric.cashu.mint.admin.cli.model.MintConfigRequest;
import xyz.tcheeric.cashu.mint.admin.cli.model.MintConfigResponse;
import xyz.tcheeric.cashu.mint.admin.cli.port.MintConfigPort;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stub implementation returning synthetic configuration state.
 */
public final class StubMintConfigPort implements MintConfigPort {

    private static final Map<String, String> BASELINE = Map.of(
        "currency", "sat",
        "maxTokens", "1000"
    );

    private final AtomicInteger revisionCounter = new AtomicInteger(1);

    @Override
    public MintConfigResponse applyConfiguration(final MintConfigRequest request) {
        final Map<String, String> merged = new LinkedHashMap<>(BASELINE);
        merged.putAll(request.parameters());
        final String revision = "rev-" + revisionCounter.getAndIncrement();
        return new MintConfigResponse(
            request.mintId(),
            revision + "@" + OffsetDateTime.now(),
            merged
        );
    }
}
