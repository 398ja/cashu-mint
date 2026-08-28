package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.nut.NutSupport;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintInfoService;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties;
import xyz.tcheeric.cashu.mint.proto.util.MintIdentityProperties;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NUT-06 shape checks over the assembled {@code /v1/info} body. Whether the map
 * agrees with the wiring is covered by {@code NutWiringContractTest}; this class
 * covers the entry shapes each NUT requires.
 */
class NUT06Test {

    private final NUT06 nut06 = new NUT06(new DefaultMintInfoService(
            new MintIdentityProperties(), new MintCapabilityProperties()));

    // NUT-04 and NUT-05 advertise the deployment's payment methods and limits,
    // and NUT-05 additionally advertises the configured fee reserve.
    @Test
    void paymentMethodEntriesCarryMethodsAndLimits() throws CashuErrorException {
        Map<String, MintInfo.Nut> nuts = nut06.mintInfo().getNuts();

        MintInfo.Nut mintNut = nuts.get(NutSupport.MINT.key());
        assertThat(mintNut.getDisabled()).isFalse();
        MintInfo.Nut.Method method = mintNut.getMethods().get(0);
        assertThat(method.getMethod()).isEqualTo("bolt11");
        assertThat(method.getUnit()).isEqualTo("sat");
        assertThat(method.getMaxAmount()).isPositive();

        MintInfo.Nut meltNut = nuts.get(NutSupport.MELT.key());
        assertThat(meltNut.getFeeReservePercent()).isEqualTo(0.05d);
    }

    // Restore signatures (NUT-09) is a simple boolean capability.
    @Test
    void restoreSignaturesIsAdvertisedAsSimpleSupport() throws CashuErrorException {
        MintInfo.Nut nut9 = nut06.mintInfo().getNuts().get(NutSupport.RESTORE_SIGNATURES.key());

        assertThat(nut9).isNotNull();
        assertThat(nut9.isSupportedSimple()).isTrue();
    }

    // P2PK spending conditions (NUT-11) are advertised so clients that gate a
    // P2PK flow on /v1/info can pick the right code path.
    @Test
    void p2pkSpendingConditionsAreAdvertised() throws CashuErrorException {
        MintInfo.Nut nut11 = nut06.mintInfo().getNuts()
                .get(NutSupport.P2PK_SPENDING_CONDITIONS.key());

        assertThat(nut11).isNotNull();
        assertThat(nut11.isSupportedSimple()).isTrue();
    }

    // NUT-17 advertises the WebSocket commands the subscription handler serves.
    @Test
    void webSocketSubscriptionsAdvertiseTheServedCommands() throws CashuErrorException {
        MintInfo.Nut nut17 = nut06.mintInfo().getNuts()
                .get(NutSupport.WEBSOCKET_SUBSCRIPTIONS.key());

        assertThat(nut17).isNotNull();
        var configs = nut17.getSupportedConfigs();
        assertThat(configs).isNotEmpty();

        var config = configs.get(0);
        assertThat(config.getMethod()).isEqualTo("bolt11");
        assertThat(config.getUnit()).isEqualTo("sat");
        assertThat(config.getCommands())
                .contains("bolt11_mint_quote", "bolt11_melt_quote", "proof_state");
    }
}
