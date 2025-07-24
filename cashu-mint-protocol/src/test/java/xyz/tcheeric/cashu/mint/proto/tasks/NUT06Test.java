package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = {NUT06.class})
@EnableConfigurationProperties(value = MintInfo.class)
class NUT06Test {

    @Autowired
    private NUT06 nut06;

    @Test
    void testInfo() {
        MintInfo mintInfo = nut06.getMintInfo();
        assertNotNull(mintInfo);

        Map<String, MintInfo.Nut> nuts = mintInfo.getNuts();

        MintInfo.Nut nut = nuts.get("4");
        assertFalse(nut.getDisabled());

        MintInfo.Nut.Method method = nut.getMethods().get(0);
        assertEquals("bolt11", method.getMethod());
        assertEquals("sat", method.getUnit());
        assertEquals(0, method.getMinAmount());


    }
}