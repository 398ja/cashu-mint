package xyz.tcheeric.cashu.mint.admin.rest.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort;
import xyz.tcheeric.cashu.mint.admin.application.port.out.KeySetInventoryPort.VaultKeySet;
import xyz.tcheeric.cashu.mint.admin.domain.AdminRole;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminApiConfiguration;
import xyz.tcheeric.cashu.mint.admin.rest.nap.NapSessionCleanup;
import xyz.tcheeric.cashu.mint.admin.rest.nap.TestNapSessions;
import xyz.tcheeric.cashu.mint.admin.rest.service.AdminLifecycleServiceConfiguration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({AdminApiConfiguration.class, AdminLifecycleServiceConfiguration.class})
@TestPropertySource(properties = {
    // Own database per class: a shared in-memory store leaks state between classes.
    "spring.datasource.url=jdbc:h2:mem:KeySetInventoryAdminControllerTest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
})
@ExtendWith(NapSessionCleanup.class)
class KeySetInventoryAdminControllerTest {

    private static final String MINT_ID = "11111111-2222-3333-4444-555555555555";
    private static final String PATH = "/admin/lifecycle/mints/" + MINT_ID + "/keysets";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KeySetInventoryPort keySetInventory;

    /**
     * A typo in the URL is the caller's mistake, not the vault's: answering 500 would send
     * an Operator looking for an outage that isn't there.
     */
    @Test
    @DisplayName("A mint id that is not a mint id is a bad request, not a vault failure")
    void malformedMintIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/admin/lifecycle/mints/not-a-uuid/keysets").with(TestNapSessions.superAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_mint_id"));
    }

    /**
     * The load-bearing case. An unreachable vault rendered as an empty list would tell an
     * Operator that a rotation destroyed key material that is in fact still there, so the
     * two outcomes must never collapse into one another.
     */
    @Test
    @DisplayName("A vault read that fails is an error, never an empty list")
    void vaultFailureIsNotAnEmptyList() throws Exception {
        given(keySetInventory.listByMint(any())).willThrow(new RuntimeException("connection refused"));

        mockMvc.perform(get(PATH).with(TestNapSessions.superAdmin()))
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.code").value("vault_unavailable"))
                .andExpect(jsonPath("$.items").doesNotExist());
    }

    // A mint that was never provisioned holds nothing, which is an answer rather than a fault.
    @Test
    @DisplayName("A mint with nothing in the vault answers an empty list")
    void unprovisionedMintAnswersEmpty() throws Exception {
        given(keySetInventory.listByMint(any())).willReturn(List.of());

        mockMvc.perform(get(PATH).with(TestNapSessions.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    /**
     * The question the page exists to answer is "did my rotation actuate", so the keyset the
     * mint signs with comes first and the keysets it replaced read newest-first behind it.
     */
    @Test
    @DisplayName("The signing keyset comes first, then archived keysets newest-first")
    void signingKeySetLeadsThenArchivedNewestFirst() throws Exception {
        final Instant now = Instant.parse("2026-08-28T10:00:00Z");
        given(keySetInventory.listByMint(UUID.fromString(MINT_ID))).willReturn(List.of(
            new VaultKeySet("00oldest", "sat", now.minusSeconds(7200), true),
            new VaultKeySet("00signing", "sat", now.minusSeconds(60), false),
            new VaultKeySet("00middle", "sat", now.minusSeconds(3600), true)));

        mockMvc.perform(get(PATH).with(TestNapSessions.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].keySetId").value("00signing"))
                .andExpect(jsonPath("$.items[0].state").value("SIGNING"))
                .andExpect(jsonPath("$.items[1].keySetId").value("00middle"))
                .andExpect(jsonPath("$.items[1].state").value("ARCHIVED"))
                .andExpect(jsonPath("$.items[2].keySetId").value("00oldest"));
    }

    // The vault stores private keys; nothing that leaves the adapter may be able to carry one.
    @Test
    @DisplayName("A keyset row carries its id, unit and creation time, and nothing else")
    void rowCarriesNoKeyMaterial() throws Exception {
        given(keySetInventory.listByMint(any())).willReturn(List.of(
            new VaultKeySet("00signing", "sat", Instant.parse("2026-08-28T10:00:00Z"), false)));

        mockMvc.perform(get(PATH).with(TestNapSessions.superAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unit").value("sat"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-08-28T10:00:00Z"))
                .andExpect(jsonPath("$.items[0].length()").value(4));
    }

    // Key material inventory is not readable by every signed-in account.
    @Test
    @DisplayName("A session without mint:lifecycle is refused")
    void requiresTheMintLifecyclePermission() throws Exception {
        mockMvc.perform(get(PATH).with(TestNapSessions.role(AdminRole.USER_ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    // The inventory is not public.
    @Test
    @DisplayName("An unauthenticated caller is refused")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
