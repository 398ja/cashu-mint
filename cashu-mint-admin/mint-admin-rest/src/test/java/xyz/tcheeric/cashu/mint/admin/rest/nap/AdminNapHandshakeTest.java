package xyz.tcheeric.cashu.mint.admin.rest.nap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

import nostr.crypto.bech32.Bech32;
import nostr.crypto.bech32.Bech32Prefix;
import nostr.crypto.schnorr.Schnorr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository;
import xyz.tcheeric.cashu.mint.admin.application.port.out.OperatorAccessRepository.OperatorAccessAccount;
import xyz.tcheeric.cashu.mint.admin.rest.config.AdminApiConfiguration;
import xyz.tcheeric.cashu.mint.admin.domain.AdminPermission;
import xyz.tcheeric.cashu.mint.admin.domain.AdminRole;
import xyz.tcheeric.nap.client.NapProofBuilder;
import xyz.tcheeric.nap.core.SessionRecord;
import xyz.tcheeric.nap.core.SessionStore;
import xyz.tcheeric.nap.spring.config.NapProperties;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives a real NIP-98 handshake against the admin API, and checks a session is
 * the only way in (issues #372, #373).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(AdminApiConfiguration.class)
@TestPropertySource(properties = {
    "nap.enabled=true",
    "nap.external-base-url=http://localhost",
    "nap.rate-limit-enabled=false",
    // NAP's migrations are PostgreSQL-only; the schema separation they need is proved
    // against a real database in NapSchemaSeparationIT.
    "nap.jdbc-stores=false",
    // Production sets this from the environment; pinned here so the flag is proved
    // to reach the cookie rather than assumed to.
    "nap.cookie.secure=true",
    "nap.min-auth-response-millis=0",
    "nap.response-jitter-millis=0",
    "spring.datasource.url=jdbc:h2:mem:AdminNapHandshakeTest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
})
class AdminNapHandshakeTest {

    private static final HexFormat HEX = HexFormat.of();
    private static final String SUPER_ADMIN_PRIVATE_KEY =
        "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String COMPLETE_URL = "http://localhost/api/v1/auth/complete";

    private static final Supplier<String> SUPER_ADMIN_NPUB = () ->
        Bech32.toBech32(Bech32Prefix.NPUB, superAdminPubkey());

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static String superAdminPubkey() {
        return HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(SUPER_ADMIN_PRIVATE_KEY)));
    }

    // The Super Administrator is a configuration fact, so it is registered as one --
    // derived from the fixed key below rather than pasted in as a literal that could
    // drift from it.
    @DynamicPropertySource
    static void superAdminNpub(final DynamicPropertyRegistry registry) {
        registry.add("admin.security.super-admin-npub", SUPER_ADMIN_NPUB::get);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OperatorAccessRepository operatorAccessRepository;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NapProperties napProperties;

    // Each test starts from an empty store rather than inheriting the previous
    // test's operator.
    @BeforeEach
    void emptyOperatorStore() {
        jdbcTemplate.execute("DELETE FROM admin_users");
    }

    // Checks the Super Administrator completes a handshake and the session reports the role
    // and every permission, without an operator profile existing for that npub.
    @Test
    @DisplayName("Super Administrator handshake yields a session carrying role and permissions")
    void superAdministratorHandshakeReturnsRoleAndPermissions() throws Exception {
        final Cookie session = handshake(SUPER_ADMIN_PRIVATE_KEY);

        final MvcResult result = mockMvc.perform(get("/api/v1/auth/session").cookie(session))
            .andExpect(status().isOk())
            .andReturn();

        final JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("roles")).singleElement().asString().contains("SUPER_ADMIN");
        assertThat(body.get("permissions").toString()).contains("mint:lifecycle", "operators:manage");
    }

    // Checks the session settings this application decides -- 15 minutes idle, 12 hours
    // absolute, no refresh tokens, its own cookie name -- reach the bound properties.
    @Test
    @DisplayName("Session lifetimes and cookie name come from this application, not NAP's defaults")
    void sessionSettingsAreThisApplicationsOwn() {
        assertThat(napProperties.sessionIdleTtlSeconds()).isEqualTo(900);
        assertThat(napProperties.sessionAbsoluteTtlSeconds()).isEqualTo(43200);
        assertThat(napProperties.refreshTtlSeconds()).isZero();
        assertThat(napProperties.cookie().name()).isEqualTo("cashu_admin_session");
    }

    // Checks an npub with no operator profile is refused: authenticating grants no default role.
    @Test
    @DisplayName("An npub without an operator profile is refused")
    void unknownNpubIsRefused() throws Exception {
        final byte[] privateKey = new byte[32];
        new SecureRandom().nextBytes(privateKey);

        assertThat(completeHandshake(HEX.formatHex(privateKey)).getResponse().getStatus())
            .isEqualTo(401);
    }

    // Checks an operator with a profile authenticates and carries that profile's permissions.
    @Test
    @DisplayName("An operator profile carries its own permissions into the session")
    void operatorProfileCarriesItsPermissions() throws Exception {
        final byte[] privateKey = new byte[32];
        new SecureRandom().nextBytes(privateKey);
        final String pubkey = HEX.formatHex(Schnorr.genPubKey(privateKey));
        operatorAccessRepository.create(new OperatorAccessAccount("nap-operator", "Nap Operator",
            "operator@example.com", Set.of("MINT_ADMIN"), true, pubkey));

        final Cookie session = handshake(HEX.formatHex(privateKey));

        final MvcResult result = mockMvc.perform(get("/api/v1/auth/session").cookie(session))
            .andExpect(status().isOk())
            .andReturn();

        final JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("roles")).singleElement().asString().contains("MINT_ADMIN");
        assertThat(body.get("permissions").toString())
            .contains("mint:lifecycle", "audit:read")
            .doesNotContain("operators:manage");
    }

    // Checks a session opens the admin API, and that nothing else does (issue #373).
    @Test
    @DisplayName("A session opens the admin API and its absence closes it")
    void adminApiAnswersOnlyOnASession() throws Exception {
        mockMvc.perform(get("/admin/users").cookie(handshake(SUPER_ADMIN_PRIVATE_KEY)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/admin/users"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("unauthorized"))
            .andExpect(jsonPath("$.message").exists());
    }

    // Checks an expired session is refused: the cookie survives its session, so the
    // cookie alone must not be what opens the door.
    @Test
    @DisplayName("An expired session is refused")
    void expiredSessionIsRefused() throws Exception {
        final long past = Instant.now().getEpochSecond() - 60;
        sessionStore.createForChallenge(SessionRecord.create("expired-session", "expired-challenge",
            "expired-token", SUPER_ADMIN_NPUB.get(), superAdminPubkey(),
            List.of(AdminRole.SUPER_ADMIN.key()), List.of(AdminPermission.Keys.USERS_MANAGE),
            past - 60, past));

        mockMvc.perform(get("/admin/users").cookie(new Cookie("cashu_admin_session", "expired-session")))
            .andExpect(status().isUnauthorized());
    }

    // Checks a session that authenticated still cannot reach a permission its roles
    // do not carry, and that the refusal is a decision rather than a bare status.
    @Test
    @DisplayName("A missing permission is refused with an error body")
    void missingPermissionIsRefusedWithABody() throws Exception {
        final byte[] privateKey = new byte[32];
        new SecureRandom().nextBytes(privateKey);
        final String pubkey = HEX.formatHex(Schnorr.genPubKey(privateKey));
        operatorAccessRepository.create(new OperatorAccessAccount("ops-only", "Ops Only",
            "ops@example.com", Set.of("OPS_ADMIN"), true, pubkey));

        mockMvc.perform(get("/admin/users").cookie(handshake(HEX.formatHex(privateKey))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("forbidden"))
            .andExpect(jsonPath("$.message").exists());
    }

    private Cookie handshake(final String privateKeyHex) throws Exception {
        final MvcResult completed = completeHandshake(privateKeyHex);
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        final Cookie cookie = completed.getResponse().getCookie("cashu_admin_session");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        return cookie;
    }

    private MvcResult completeHandshake(final String privateKeyHex) throws Exception {
        final String pubkey = HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(privateKeyHex)));
        final String npub = Bech32.toBech32(Bech32Prefix.NPUB, pubkey);

        final MvcResult init = mockMvc.perform(post("/api/v1/auth/init")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"npub\":\"" + npub + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
        final JsonNode challenge = objectMapper.readTree(init.getResponse().getContentAsString());

        final String body = "{\"challenge_id\":\"" + challenge.get("challenge_id").asText() + "\"}";
        final String authorization = new NapProofBuilder()
            .privateKey(privateKeyHex)
            .pubkey(pubkey)
            .url(COMPLETE_URL)
            .method("POST")
            .challenge(challenge.get("challenge").asText())
            .challengeId(challenge.get("challenge_id").asText())
            .body(body)
            .buildAuthorizationHeader();

        return mockMvc.perform(post("/api/v1/auth/complete")
                .header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andReturn();
    }
}
