package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.service.impl.DefaultMintInfoService;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = {NUT06.class, DefaultMintInfoService.class})
@EnableConfigurationProperties(value = MintInfo.class)
class NUT06Test {

    @Autowired
    private NUT06 nut06;

    @Test
    // Ensures mint info YAML is parsed and provides NUT-04 and NUT-05 details
    void testInfo() throws CashuErrorException {
        MintInfo mintInfo = nut06.mintInfo();
        assertNotNull(mintInfo);

        Map<String, MintInfo.Nut> nuts = mintInfo.getNuts();

        MintInfo.Nut nut = nuts.get("4");
        assertFalse(nut.getDisabled());

        MintInfo.Nut.Method method = nut.getMethods().get(0);
        assertEquals("bolt11", method.getMethod());
        assertEquals("sat", method.getUnit());
        assertEquals(0, method.getMinAmount());

        MintInfo.Nut nut5 = nuts.get("5");
        assertEquals(0.05d, nut5.getFeeReservePercent(), 0.0001);
    }

    @Test
    // Ensures the mint advertises support for NUT-09 restore signatures
    void nut9Supported() throws CashuErrorException {
        MintInfo mintInfo = nut06.mintInfo();
        Map<String, MintInfo.Nut> nuts = mintInfo.getNuts();

        MintInfo.Nut nut9 = nuts.get("9");
        assertNotNull(nut9);
        assertTrue(Boolean.TRUE.equals(nut9.getSupported()));
    }
}
