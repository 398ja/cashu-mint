package xyz.tcheeric.cashu.mint.proto.service;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintProtocolService;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;
import xyz.tcheeric.gateway.common.Gateway;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class DefaultMintProtocolServiceTest {

    /**
     * Verifies that DefaultMintProtocolService resolves the unit for a method from NUT-06 (MintInfo)
     * and loads the gateway class configured under gateway.<method>.<unit>.
     */
    @Test
    void resolvesUnitFromNut06_usesUnitSpecificMapping() {
        // Arrange MintInfo with bolt11 sat configuration
        MintInfo mintInfo = new MintInfo();
        MintInfo.Nut.Method method = new MintInfo.Nut.Method();
        method.setMethod("bolt11");
        method.setUnit("sat");
        MintInfo.Nut nut = new MintInfo.Nut();
        nut.setMethods(List.of(method));
        Map<String, MintInfo.Nut> nuts = new HashMap<>();
        nuts.put("4", nut); // NUT-04
        mintInfo.setNuts(nuts);

        MintInfoService mintInfoService = Mockito.mock(MintInfoService.class);
        Mockito.when(mintInfoService.getMintInfo()).thenReturn(mintInfo);

        DefaultMintProtocolService service = new DefaultMintProtocolService();
        service.setMintInfoService(mintInfoService);

        // Act
        Gateway gateway = service.createGateway(PaymentMethod.BOLT11);

        // Assert
        assertNotNull(gateway);
        assertEquals("xyz.tcheeric.gateway.phoenixd.PhoenixdGateway", gateway.getClass().getName());
    }

    /**
     * Verifies that when the resolved unit has no explicit mapping, the loader falls back to
     * gateway.<method> mapping.
     */
    @Test
    void resolvesUnknownUnit_fallsBackToMethodMapping() {
        // Arrange MintInfo with bolt11 usd (no unit-specific mapping in test properties)
        MintInfo mintInfo = new MintInfo();
        MintInfo.Nut.Method method = new MintInfo.Nut.Method();
        method.setMethod("bolt11");
        method.setUnit("usd");
        MintInfo.Nut nut = new MintInfo.Nut();
        nut.setMethods(List.of(method));
        Map<String, MintInfo.Nut> nuts = new HashMap<>();
        nuts.put("4", nut);
        mintInfo.setNuts(nuts);

        MintInfoService mintInfoService = Mockito.mock(MintInfoService.class);
        Mockito.when(mintInfoService.getMintInfo()).thenReturn(mintInfo);

        DefaultMintProtocolService service = new DefaultMintProtocolService();
        service.setMintInfoService(mintInfoService);

        // Act
        Gateway gateway = service.createGateway(PaymentMethod.BOLT11);

        // Assert: falls back to gateway.bolt11 which also maps to PhoenixdGateway in test properties
        assertNotNull(gateway);
        assertEquals("xyz.tcheeric.gateway.phoenixd.PhoenixdGateway", gateway.getClass().getName());
    }
}

