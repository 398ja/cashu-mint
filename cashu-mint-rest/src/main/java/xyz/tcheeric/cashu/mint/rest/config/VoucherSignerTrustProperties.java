package xyz.tcheeric.cashu.mint.rest.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherIssuerBindingMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The keys the mint trusts to sign vouchers, and what it does about one it does not
 * (cashu-mint#527). Bound under {@code cashu.mint.voucher}, beside
 * {@link VoucherDurabilityProperties}, and registered whether or not {@code voucher.enabled} is
 * on, because a voucher can be swapped at a mint that does not issue them.
 *
 * <ul>
 *   <li>{@code issuer-keys.<issuerId>} — the key one issuer signs with. Also the trust anchor
 *       merchant verification checks against, so both read this one map.</li>
 *   <li>{@code trusted-signers} — comma-separated keys that sign on behalf of any issuer, such as
 *       the customer gateway's identity key.</li>
 *   <li>{@code issuer-binding} — {@code off}, {@code log} (default) or {@code enforce}.</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "cashu.mint.voucher")
public class VoucherSignerTrustProperties {

    /** Issuer id to the hex key that issuer signs with, x-only or compressed. */
    private Map<String, String> issuerKeys = new LinkedHashMap<>();

    /** Hex keys trusted to sign for any issuer, x-only or compressed. */
    private List<String> trustedSigners = new ArrayList<>();

    /** What to do with a signed voucher whose signer is not trusted for its issuer. */
    private VoucherIssuerBindingMode issuerBinding = VoucherIssuerBindingMode.LOG;

    public void setIssuerKeys(Map<String, String> issuerKeys) {
        this.issuerKeys = issuerKeys == null ? new LinkedHashMap<>() : issuerKeys;
    }

    public void setTrustedSigners(List<String> trustedSigners) {
        this.trustedSigners = trustedSigners == null ? new ArrayList<>() : trustedSigners;
    }
}
