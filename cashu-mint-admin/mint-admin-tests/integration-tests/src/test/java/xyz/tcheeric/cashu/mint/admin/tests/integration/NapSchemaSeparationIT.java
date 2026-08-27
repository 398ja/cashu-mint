package xyz.tcheeric.cashu.mint.admin.tests.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nostr.crypto.bech32.Bech32;
import nostr.crypto.bech32.Bech32Prefix;
import nostr.crypto.schnorr.Schnorr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import xyz.tcheeric.cashu.mint.admin.rest.CashuMintAdminRestApplication;
import xyz.tcheeric.cashu.mint.admin.tests.integration.infrastructure.PostgresContainerExtension;
import xyz.tcheeric.nap.client.NapProofBuilder;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * NAP's own migrations run unmodified in their own PostgreSQL schema, with their own
 * history table, alongside the admin's — and the sessions they back land there (issue #372).
 */
@SpringBootTest(classes = CashuMintAdminRestApplication.class)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(PostgresContainerExtension.class)
class NapSchemaSeparationIT {

    private static final HexFormat HEX = HexFormat.of();
    private static final String SUPER_ADMIN_PRIVATE_KEY =
        "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String COMPLETE_URL = "http://localhost/api/v1/auth/complete";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void configureProperties(final DynamicPropertyRegistry registry) {
        PostgresContainerExtension.registerProperties(registry);
        registry.add("nap.enabled", () -> true);
        registry.add("nap.external-base-url", () -> "http://localhost");
        registry.add("nap.rate-limit-enabled", () -> false);
        registry.add("nap.min-auth-response-millis", () -> 0);
        registry.add("nap.response-jitter-millis", () -> 0);
        registry.add("admin.security.super-admin-npub", () -> Bech32.toBech32(Bech32Prefix.NPUB,
            HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(SUPER_ADMIN_PRIVATE_KEY)))));
    }

    // Checks both migration series ran, apart: neither history table records the other's versions.
    @Test
    @DisplayName("NAP migrations run in their own schema with their own history table")
    void napMigrationsAreSeparate() {
        assertThat(tableSchemas("nap_sessions")).containsExactly("nap");
        assertThat(tableSchemas("nap_schema_history")).containsExactly("nap");
        assertThat(tableSchemas("admin_users")).containsExactly("public");
        assertThat(tableSchemas("flyway_schema_history")).containsExactly("public");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nap.nap_schema_history", Integer.class)).isPositive();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM public.flyway_schema_history"
                + " WHERE script IN (SELECT script FROM nap.nap_schema_history)",
            Integer.class)).isZero();
    }

    // Checks the JDBC stores work against the real database, writing into the nap schema.
    @Test
    @DisplayName("A completed handshake persists a session in the nap schema")
    void handshakePersistsSession() throws Exception {
        final String pubkey = HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(SUPER_ADMIN_PRIVATE_KEY)));
        final String npub = Bech32.toBech32(Bech32Prefix.NPUB, pubkey);

        final MvcResult init = mockMvc.perform(post("/api/v1/auth/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"npub\":\"" + npub + "\"}"))
            .andReturn();
        final JsonNode challenge = objectMapper.readTree(init.getResponse().getContentAsString());

        final String body = "{\"challenge_id\":\"" + challenge.get("challenge_id").asText() + "\"}";
        final String authorization = new NapProofBuilder()
            .privateKey(SUPER_ADMIN_PRIVATE_KEY)
            .pubkey(pubkey)
            .url(COMPLETE_URL)
            .method("POST")
            .challenge(challenge.get("challenge").asText())
            .challengeId(challenge.get("challenge_id").asText())
            .body(body)
            .buildAuthorizationHeader();

        final MvcResult completed = mockMvc.perform(post("/api/v1/auth/complete")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .content(body))
            .andReturn();

        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        assertThat(completed.getResponse().getContentAsString()).contains("SUPER_ADMIN");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM nap.nap_sessions", Integer.class)).isPositive();
    }

    private List<String> tableSchemas(final String table) {
        return jdbcTemplate.queryForList(
            "SELECT table_schema FROM information_schema.tables WHERE table_name = ?",
            String.class, table);
    }
}
