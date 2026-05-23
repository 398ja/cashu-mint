package xyz.tcheeric.cashu.mint.proto.nut.spec003;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 003 FR-010 / SC-004 / T070 — guard that the NUT-06 info source-of-truth
 * ({@code mint.yaml}) does not include a {@code voucher} entry under the
 * {@code nuts:} key. Vouchers are a non-standard vendor extension on top of
 * NUT-04 ({@link <a href="https://github.com/cashubtc/nuts/blob/main/04.md">NUT-04</a>});
 * Constitution II prohibits advertising them as a NUT.
 *
 * <p>Parses {@code mint.yaml} via SnakeYAML (same idiom as
 * {@code NutAdvertisementContractTest}) so the assertion is structural,
 * not string-based — a stray {@code voucher} token in a code comment or
 * a nested key never produces a false positive or negative.
 */
class VoucherNutAdvertisementGuardTest {

    @Test
    @SuppressWarnings("unchecked")
    void mintYamlNutsKeyDoesNotAdvertiseVoucher() throws Exception {
        Yaml yaml = new Yaml();
        try (InputStream in = new ClassPathResource("mint.yaml").getInputStream()) {
            Map<String, Object> root = yaml.load(in);
            assertThat(root).as("mint.yaml must parse to a map").isNotNull();

            Map<String, Object> mint = (Map<String, Object>) root.get("mint");
            assertThat(mint).as("mint.yaml must contain a `mint:` root key").isNotNull();

            Map<Object, Object> nuts = (Map<Object, Object>) mint.get("nuts");
            assertThat(nuts).as("mint.yaml must contain a `mint.nuts` map").isNotNull();

            for (Object key : nuts.keySet()) {
                assertThat(key.toString().toLowerCase())
                        .as("FR-010: nuts key '%s' MUST NOT be voucher-related — "
                                + "vouchers are a non-standard vendor extension on top of NUT-04",
                                key)
                        .doesNotContain("voucher");
            }
        }
    }
}
