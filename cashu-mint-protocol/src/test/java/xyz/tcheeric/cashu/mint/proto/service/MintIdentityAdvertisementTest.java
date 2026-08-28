package xyz.tcheeric.cashu.mint.proto.service;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.mint.proto.nut.NutSupport;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintInfoService;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties;
import xyz.tcheeric.cashu.mint.proto.util.MintIdentityProperties;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #390 — the mint's advertised identity must come from the deployment,
 * never from a value baked into the jar.
 *
 * @see <a href="https://github.com/cashubtc/nuts/blob/main/06.md">NUT-06</a>
 */
class MintIdentityAdvertisementTest {

    private static final List<String> RETIRED_PLACEHOLDERS = List.of(
            "Bob's Cashu mint",
            "Nutshell",
            "mint.host",
            ".onion",
            "0283bf290884eed3a7ca2663fc0260de2e2064d6b355ea13f98dec004b7a7ead99",
            "contact@me.com",
            "npub...");

    private final MintCapabilityProperties capabilities = new MintCapabilityProperties();

    // An undeclared identity advertises nothing rather than a shipped placeholder,
    // so no two deployments of this codebase can claim the same identity.
    @Test
    void undeclaredIdentityAdvertisesNothing() {
        MintInfo info = infoFor(new MintIdentityProperties());

        assertThat(info.getName()).isNull();
        assertThat(info.getPubkey()).isNull();
        assertThat(info.getDescription()).isNull();
        assertThat(info.getMotd()).isNull();
        assertThat(info.getUrls()).isEmpty();
        assertThat(info.getContact()).isEmpty();
    }

    // None of the placeholder values that shipped in mint.yaml can reappear in a
    // default response; this is the exit criterion of issue #390.
    @Test
    void noPlaceholderIdentitySurvives() {
        String rendered = renderIdentity(infoFor(new MintIdentityProperties()));

        for (String placeholder : RETIRED_PLACEHOLDERS) {
            assertThat(rendered)
                    .as("retired placeholder '%s' must not appear in /v1/info", placeholder)
                    .doesNotContain(placeholder);
        }
    }

    // Identity supplied by the deployment is what the mint advertises.
    @Test
    void deploymentSuppliedIdentityIsAdvertised() {
        MintIdentityProperties identity = new MintIdentityProperties();
        identity.setName("Imani Mint");
        identity.setPubkey("02" + "a".repeat(64));
        identity.setUrls(List.of("https://mint.imani.example", "   "));
        identity.setContacts(List.of("email:ops@imani.example", "malformed-entry"));

        MintInfo info = infoFor(identity);

        assertThat(info.getName()).isEqualTo("Imani Mint");
        assertThat(info.getUrls()).containsExactly("https://mint.imani.example");
        assertThat(info.getContact()).hasSize(1);
        assertThat(info.getContact().get(0).getMethod()).isEqualTo("email");
        assertThat(info.getContact().get(0).getInfo()).isEqualTo("ops@imani.example");
    }

    // The version identifies this implementation and its build, never Nutshell.
    @Test
    void versionComesFromTheBuild() {
        String version = infoFor(new MintIdentityProperties()).getVersion();

        assertThat(version).startsWith("cashu-mint/");
        assertThat(version).doesNotContain("unknown");
    }

    // Vouchers and trace events are vendor extensions, not NUTs (Constitution II),
    // so they must never appear as keys in the advertised map.
    @Test
    void vendorExtensionsAreNotAdvertisedAsNuts() {
        for (NutSupport nut : NutSupport.values()) {
            assertThat(nut.name().toLowerCase())
                    .as("NutSupport entry '%s' must not be a vendor extension", nut)
                    .doesNotContain("voucher")
                    .doesNotContain("trace");
        }
    }

    private MintInfo infoFor(MintIdentityProperties identity) {
        return new DefaultMintInfoService(identity, capabilities).getMintInfo();
    }

    private String renderIdentity(MintInfo info) {
        return String.join("|",
                String.valueOf(info.getName()),
                String.valueOf(info.getPubkey()),
                String.valueOf(info.getVersion()),
                String.valueOf(info.getDescription()),
                String.valueOf(info.getDescriptionLong()),
                String.valueOf(info.getMotd()),
                String.valueOf(info.getIconUrl()),
                String.valueOf(info.getTosUrl()),
                String.valueOf(info.getUrls()),
                String.valueOf(info.getContact()));
    }
}
