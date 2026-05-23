package xyz.tcheeric.cashu.mint.proto.nut.spec003;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 003 FR-010 / SC-004 / T070 — guard that the NUT-06 info source-of-truth
 * ({@code mint.yaml}) does not include a {@code voucher} entry under the
 * {@code nuts:} key. Vouchers are a non-standard vendor extension on top of
 * NUT-04 ({@link <a href="https://github.com/cashubtc/nuts/blob/main/04.md">NUT-04</a>});
 * Constitution II prohibits advertising them as a NUT.
 *
 * <p>This is a static text check on the classpath resource — fast, no Spring
 * context, runs as a unit test.
 */
class VoucherNutAdvertisementGuardTest {

    @Test
    void mintYamlNutsKeyDoesNotAdvertiseVoucher() {
        String mintYaml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("mint.yaml")) {
            assertThat(in).as("mint.yaml on classpath").isNotNull();
            try (Scanner scanner = new Scanner(in).useDelimiter("\\A")) {
                mintYaml = scanner.hasNext() ? scanner.next() : "";
            }
        } catch (Exception e) {
            throw new AssertionError("Failed to read mint.yaml", e);
        }

        int nutsIdx = mintYaml.indexOf("\n  nuts:");
        assertThat(nutsIdx).as("mint.yaml must contain a nuts: key").isGreaterThan(0);

        // Slice off the lines from "  nuts:" to the next top-level (column-0)
        // key OR end-of-file. The voucher token MUST NOT appear in that slice.
        int sliceStart = nutsIdx + 1;
        int sliceEnd = mintYaml.length();
        int nextTopLevel = mintYaml.indexOf("\n#", sliceStart);
        if (nextTopLevel > 0) {
            sliceEnd = nextTopLevel;
        }
        String nutsSlice = mintYaml.substring(sliceStart, sliceEnd).toLowerCase();
        assertThat(nutsSlice)
                .as("FR-010: vouchers MUST NOT be advertised under the NUT-06 nuts key")
                .doesNotContain("voucher");
    }
}
