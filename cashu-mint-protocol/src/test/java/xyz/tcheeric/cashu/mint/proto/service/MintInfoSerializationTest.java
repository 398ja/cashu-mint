package xyz.tcheeric.cashu.mint.proto.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.nut.NutSupport;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintInfoService;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties;
import xyz.tcheeric.cashu.mint.proto.util.MintIdentityProperties;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #390 — pins the serialised {@code /v1/info} body, because the wire shape
 * is what wallets actually parse. The Java model agreeing with the registry is
 * necessary but not sufficient: derived accessors leaked {@code supportedSimple}
 * and {@code supportedConfigs} into the payload until this test caught it.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/06.md">NUT-06</a>
 */
class MintInfoSerializationTest {

    private static final List<String> NON_SPEC_FIELDS =
            List.of("supportedSimple", "supportedConfigs", "cachedEndpoints", "descriptionLong");

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode render() {
        MintIdentityProperties identity = new MintIdentityProperties();
        identity.setName("Imani Mint");
        var info = new DefaultMintInfoService(identity, new MintCapabilityProperties()).getMintInfo();
        return mapper.valueToTree(info);
    }

    // The payload must carry only NUT-06 field names; a derived Java accessor
    // must never appear on the wire as a camelCase sibling of a spec field.
    @Test
    void payloadContainsNoNonSpecFieldNames() {
        List<String> found = new ArrayList<>();
        collectFieldNames(render(), found);

        assertThat(found)
                .as("/v1/info must serialise NUT-06 field names only")
                .doesNotContainAnyElementsOf(NON_SPEC_FIELDS);
    }

    // NUT-19 serialises `ttl` and snake_case `cached_endpoints` as the spec shows.
    @Test
    void nut19SerialisesWithSpecFieldNames() {
        JsonNode nut19 = render().get("nuts").get(NutSupport.CACHED_RESPONSES.key());

        assertThat(nut19.get("ttl").asLong()).isPositive();
        assertThat(nut19.get("cached_endpoints").isArray()).isTrue();
        assertThat(nut19.get("cached_endpoints").get(0).get("method").asText()).isEqualTo("POST");
        assertThat(nut19.get("cached_endpoints").get(0).get("path").asText()).startsWith("/v1/");
    }

    // Unset identity fields are omitted entirely rather than serialised as null,
    // so a wallet never reads a null where it expects a string.
    @Test
    void unsetIdentityFieldsAreOmitted() {
        JsonNode payload = render();

        assertThat(payload.has("motd")).isFalse();
        assertThat(payload.has("pubkey")).isFalse();
        assertThat(payload.get("name").asText()).isEqualTo("Imani Mint");
        assertThat(payload.get("version").asText()).startsWith("cashu-mint/");
    }

    private void collectFieldNames(JsonNode node, List<String> into) {
        node.fieldNames().forEachRemaining(into::add);
        node.forEach(child -> collectFieldNames(child, into));
    }
}
