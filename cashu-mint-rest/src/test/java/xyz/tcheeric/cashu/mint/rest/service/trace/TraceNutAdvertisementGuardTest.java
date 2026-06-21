package xyz.tcheeric.cashu.mint.rest.service.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

/**
 * Spec 036 (Constitution II) — guard that the trace producer is never advertised
 * as a Cashu NUT. Trace events are a non-standard observability extension; like
 * vouchers they MUST NOT appear under the NUT-06 {@code nuts:} key. Mirrors
 * {@code VoucherNutAdvertisementGuardTest}; structural parse of {@code mint.yaml}
 * so a stray token in a comment never trips it.
 */
class TraceNutAdvertisementGuardTest {

    @Test
    @SuppressWarnings("unchecked")
    void mintYamlNutsKeyDoesNotAdvertiseTrace() throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream in = new ClassPathResource("mint.yaml").getInputStream()) {
            Map<String, Object> root = yaml.load(in);
            assertThat(root).as("mint.yaml must parse to a map").isNotNull();

            Map<String, Object> mint = (Map<String, Object>) root.get("mint");
            assertThat(mint).as("mint.yaml must contain a `mint:` root key").isNotNull();

            Map<Object, Object> nuts = (Map<Object, Object>) mint.get("nuts");
            assertThat(nuts).as("mint.yaml must contain a `mint.nuts` map").isNotNull();

            for (Object key : nuts.keySet()) {
                String k = key.toString().toLowerCase();
                assertThat(k)
                        .as("nuts key '%s' MUST NOT advertise the trace extension — "
                                + "trace events are a non-standard observability extension, not a NUT", key)
                        .doesNotContain("trace")
                        .doesNotContain("9079");
            }
        }
    }
}
