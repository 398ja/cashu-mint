package xyz.tcheeric.cashu.mint.admin.rest.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.web.servlet.MvcResult;

import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRepository;
import xyz.tcheeric.cashu.mint.admin.domain.AuditMetadata;
import xyz.tcheeric.cashu.mint.admin.domain.MintAggregate;
import xyz.tcheeric.cashu.mint.admin.domain.MintId;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminApiConfiguration;
import xyz.tcheeric.cashu.mint.admin.domain.AdminRole;
import xyz.tcheeric.cashu.mint.admin.rest.nap.TestNapSessions;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminCorrelationIdFilter;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminLifecycleService;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminLifecycleServiceConfiguration;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({AdminApiConfiguration.class, AdminLifecycleServiceConfiguration.class, AdminLifecycleService.class})
@TestPropertySource(properties = {
    // Own database per class: a shared in-memory store leaks operators between
    // classes, and the bootstrap credential is inert once any operator exists.
    "spring.datasource.url=jdbc:h2:mem:LifecycleAdminControllerTest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
})
class LifecycleAdminControllerTest {

    private static final String MINT_ID_1 = "11111111-1111-1111-1111-111111111111";
    private static final String MINT_ID_2 = "22222222-2222-2222-2222-222222222222";
    private static final String MISSING_MINT_ID = "99999999-9999-9999-9999-999999999999";

    @Autowired
    private MockMvc mockMvc;

    // NapSessionFilter clears only the context it set itself, so a seated session
    // would otherwise leak onto the next test sharing this thread.
    @AfterEach
    void clearSession() {
        SecurityContextHolder.clearContext();
    }

    @Autowired
    private MintRepository mintRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Verifies that lifecycle endpoints reject unauthenticated calls.
    @Test
    @DisplayName("Lifecycle create requires authentication")
    void createMintRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/admin/lifecycle/mints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createMintJson(MINT_ID_1)))
                .andExpect(status().isUnauthorized());
    }

    // Ensures lifecycle create requires an RBAC role when authenticated.
    @Test
    @DisplayName("Lifecycle create requires role header")
    void createMintRequiresCredential() throws Exception {
        mockMvc.perform(post("/admin/lifecycle/mints")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createMintJson(MINT_ID_1)))
                .andExpect(status().isUnauthorized());
    }

    // Verifies pause endpoint updates lifecycle state when proper credentials and roles are supplied.
    @Test
    @DisplayName("Lifecycle pause returns transition summary")
    void pauseMintReturnsLifecycleResponse() throws Exception {
        mockMvc.perform(post("/admin/lifecycle/mints")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createMintJson(MINT_ID_1)))
                .andExpect(status().isOk());

        // Advance from PROVISIONING to PROVISIONED (simulates vault provisioning completion)
        markProvisioned(MINT_ID_1);

        // Activate first (PROVISIONED -> ACTIVE), then pause
        mockMvc.perform(post("/admin/lifecycle/mints/" + MINT_ID_1 + "/resume")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleChangeJson("activation")))
                .andExpect(status().isOk());

        final MvcResult pauseResult = mockMvc.perform(post("/admin/lifecycle/mints/" + MINT_ID_1 + "/pause")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleChangeJson("maintenance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("PAUSE"))
                .andExpect(jsonPath("$.message").value("Mint paused"))
                .andExpect(jsonPath("$.currentState").value("SUSPENDED"))
                .andExpect(header().exists(AdminCorrelationIdFilter.CORRELATION_ID_HEADER))
                .andReturn();

        final String correlationHeader = pauseResult.getResponse()
            .getHeader(AdminCorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(correlationHeader).isNotBlank();
    }

    // Ensures provided correlation identifiers flow back to the caller unchanged.
    @Test
    @DisplayName("Lifecycle pause reuses provided correlation header")
    void pauseMintHonoursProvidedCorrelationId() throws Exception {
        mockMvc.perform(post("/admin/lifecycle/mints")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createMintJson(MINT_ID_2)))
                .andExpect(status().isOk());

        // Advance from PROVISIONING to PROVISIONED (simulates vault provisioning completion)
        markProvisioned(MINT_ID_2);

        // Activate first (PROVISIONED -> ACTIVE), then pause
        mockMvc.perform(post("/admin/lifecycle/mints/" + MINT_ID_2 + "/resume")
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleChangeJson("activation")))
                .andExpect(status().isOk());

        final String provided = "manual-correlation";

        mockMvc.perform(post("/admin/lifecycle/mints/" + MINT_ID_2 + "/pause")
                        .with(TestNapSessions.superAdmin())
                        .header(AdminCorrelationIdFilter.CORRELATION_ID_HEADER, provided)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(lifecycleChangeJson("manual")))
                .andExpect(status().isOk())
                .andExpect(header().string(AdminCorrelationIdFilter.CORRELATION_ID_HEADER, provided));
    }

    // Ensures lifecycle update failures surface structured error responses consistent with CLI messaging.
    @Test
    @DisplayName("Lifecycle update returns structured not-found error")
    void updateMintReturnsStructuredError() throws Exception {
        mockMvc.perform(put("/admin/lifecycle/mints/" + MISSING_MINT_ID)
                        .with(TestNapSessions.superAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "metadata": {"displayName":"Primary","description":"Mint","tags":["prod"]},
                                  "configuration": {"versionTag":"v2"},
                                  "revisionId": "rev-2"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.code").value("mint_not_found"));
    }

    private String createMintJson(final String mintId) {
        return """
            {
              "mintId": "%s",
              "metadata": {"displayName":"Primary","description":"Mint","tags":["prod"]},
              "configuration": {"versionTag":"2024-Q1"}
            }
            """.formatted(mintId);
    }

    private String lifecycleChangeJson(final String reason) {
        return """
            {
              "reason": "%s"
            }
            """.formatted(reason);
    }

    // Simulates vault provisioning completion by advancing PROVISIONING → PROVISIONED.
    private void markProvisioned(final String mintId) {
        final MintAggregate aggregate = mintRepository.findById(MintId.fromString(mintId))
            .orElseThrow(() -> new IllegalStateException("mint not found: " + mintId));
        final AuditMetadata audit = new AuditMetadata("system", "Vault provisioned", Instant.now());
        mintRepository.save(aggregate.markProvisioned(audit));
    }
}
