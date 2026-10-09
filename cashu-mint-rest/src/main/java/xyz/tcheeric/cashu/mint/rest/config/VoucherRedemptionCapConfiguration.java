package xyz.tcheeric.cashu.mint.rest.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherRedemptionCapMode;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherRedemptionLedger;
import xyz.tcheeric.cashu.mint.proto.voucher.InstalledVoucherRedemptionCap;
import xyz.tcheeric.cashu.mint.proto.voucher.VoucherRedemptionCap;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Wires the per-voucher redemption cap (cashu-mint#540).
 *
 * <ul>
 *   <li>{@code cashu.mint.voucher.redemption-cap}: {@code off}, {@code log} (default) or
 *       {@code enforce}.</li>
 *   <li>{@code cashu.mint.voucher.redeemer-tokens}: comma-separated tokens a redeemer presents in
 *       {@code X-Cashu-Voucher-Redeemer} to declare a redemption. One per service, so the logs
 *       say who declared.</li>
 * </ul>
 *
 * <p>{@code enforce} without the durable store or without a token refuses to boot, as #527's
 * issuer binding does: it would otherwise either refuse every declaration or refuse nothing.
 */
@Slf4j
@Configuration
public class VoucherRedemptionCapConfiguration {

    @Bean
    public VoucherRedemptionCap voucherRedemptionCap(
            @Value("${cashu.mint.voucher.redemption-cap:log}") String mode,
            @Value("${cashu.mint.voucher.redeemer-tokens:}") String redeemerTokens,
            ObjectProvider<VoucherRedemptionLedger> ledger) {
        VoucherRedemptionCapMode parsed = VoucherRedemptionCapMode.valueOf(mode.strip().toUpperCase(Locale.ROOT));
        List<String> tokens = Arrays.stream(redeemerTokens.split(","))
                .map(String::strip)
                .filter(t -> !t.isEmpty())
                .toList();
        VoucherRedemptionLedger store = ledger.getIfAvailable();
        VoucherRedemptionCap cap = new VoucherRedemptionCap(parsed, store, tokens);
        InstalledVoucherRedemptionCap.install(cap);
        if (parsed != VoucherRedemptionCapMode.OFF && (store == null || tokens.isEmpty())) {
            log.warn("voucher_redemption_cap mode={} ledger={} redeemerTokens={}: declarations will be "
                    + "{} until both are configured", parsed.label(), store != null, tokens.size(),
                    tokens.isEmpty() ? "refused as unauthorised" : "unrecorded");
        }
        log.info("voucher_redemption_cap installed mode={} ledger={} redeemerTokens={}",
                parsed.label(), store != null, tokens.size());
        return cap;
    }
}
