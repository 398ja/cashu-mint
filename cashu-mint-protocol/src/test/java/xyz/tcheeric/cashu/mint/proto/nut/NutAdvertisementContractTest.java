package xyz.tcheeric.cashu.mint.proto.nut;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 001 SC-007 / T903 — pins the set of NUT numbers advertised in
 * {@code mint.yaml} against the set of NUTs that actually have unit or
 * integration test coverage in this repository. If a NUT is added to (or
 * removed from) the YAML, this test fails until the {@code EXPECTED}
 * constant below is updated — forcing an explicit decision about whether the
 * advertisement is honest.
 *
 * <p>The full integration assertion (every advertised NUT has a passing
 * Testcontainers-backed IT) is deferred to a future
 * {@code NutAdvertisementContractIT} once the Testcontainers harness lands
 * in {@code cashu-mint-rest-it}. This test is the unit-level signpost.
 */
class NutAdvertisementContractTest {

    /**
     * The set of NUT numbers that {@code mint.yaml} is permitted to advertise.
     * Every entry MUST have at least one passing test in this repository
     * (unit or integration). Inventory below — last reviewed 2026-05-22:
     *
     * <ul>
     *   <li>NUT-04 — {@code MintTaskTest}, {@code MintTaskAmountValidationTest}, {@code MintQuoteTaskTest}</li>
     *   <li>NUT-05 — {@code MeltTaskTest}, {@code MeltQuoteTaskTest}</li>
     *   <li>NUT-07 — {@code CheckStateTaskTest}</li>
     *   <li>NUT-08 — supported claim covered by {@code MeltTask} fee-return tests</li>
     *   <li>NUT-09 — {@code RestoreSignaturesTask} + {@code MintThenRestoreIntegrationTest}</li>
     *   <li>NUT-10 — spending-condition tests under {@code proto.tasks.validator}</li>
     *   <li>NUT-11 — {@code P2PKSpendingConditionTest}</li>
     *   <li>NUT-12 — {@code DLEQProofGenerator} + tests</li>
     *   <li>NUT-17 — {@code Nut17WebSocketIT} + {@code NUT17Test}</li>
     * </ul>
     */
    private static final Set<Integer> EXPECTED = Set.of(4, 5, 7, 8, 9, 10, 11, 12, 17);

    @Test
    @SuppressWarnings("unchecked")
    void advertisedNutsMustMatchTestedNuts() throws Exception {
        Yaml yaml = new Yaml();
        Set<Integer> advertised = new TreeSet<>();
        try (InputStream in = new ClassPathResource("mint.yaml").getInputStream()) {
            Map<String, Object> root = yaml.load(in);
            Map<String, Object> mint = (Map<String, Object>) root.get("mint");
            assertThat(mint).as("mint.yaml must contain a `mint:` root key").isNotNull();
            Map<Object, Object> nuts = (Map<Object, Object>) mint.get("nuts");
            assertThat(nuts).as("mint.yaml must contain a `mint.nuts` map").isNotNull();
            for (Object key : nuts.keySet()) {
                if (key instanceof Integer i) {
                    advertised.add(i);
                } else {
                    try {
                        advertised.add(Integer.parseInt(key.toString()));
                    } catch (NumberFormatException ignored) {
                        // skip defensively
                    }
                }
            }
        }

        assertThat(advertised)
                .as("mint.yaml advertised NUTs vs. the test-backed allow list "
                        + "(see Spec 001 SC-007 / T903; update the EXPECTED constant "
                        + "when adding or removing an advertisement, and confirm each entry "
                        + "still has a passing test).")
                .isEqualTo(new TreeSet<>(EXPECTED));
    }
}
