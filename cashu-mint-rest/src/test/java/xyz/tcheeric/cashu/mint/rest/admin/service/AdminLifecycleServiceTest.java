package xyz.tcheeric.cashu.mint.rest.admin.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import xyz.tcheeric.cashu.mint.admin.framework.CorrelationIdContext;
import xyz.tcheeric.cashu.mint.rest.admin.dto.common.ActorDto;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.CreateMintRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleActionResponse;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.LifecycleChangeRequest;
import xyz.tcheeric.cashu.mint.rest.admin.dto.lifecycle.MintMetadataDto;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminLifecycleServiceTest {

    private final AdminLifecycleService service = new AdminLifecycleService();

    @AfterEach
    void tearDown() {
        CorrelationIdContext.clear();
    }

    // Ensures lifecycle transitions adopt the correlation identifier from the request context.
    @Test
    void shouldUseContextCorrelationIdWhenRequestOmitsValue() {
        service.createMint(new CreateMintRequest(
                "mint-ctx",
                new ActorDto("ops", "Ops"),
                new MintMetadataDto("Mint", "Primary mint", List.of("prod")),
                Map.of("versionTag", "v1")
        ));

        CorrelationIdContext.init("rest-generated-id");

        final LifecycleActionResponse response = service.pauseMint("mint-ctx", new LifecycleChangeRequest(
                new ActorDto("ops", "Ops"),
                "maintenance",
                null
        ));

        assertThat(response.versionTag()).isEqualTo("rest-generated-id");
    }
}
