package xyz.tcheeric.cashu.mint.proto.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.common.Witness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WitnessJsonTest {

    private static final String SIG = "1589b74b7ac37c858555fead9092cac231b62c0cda816a455a372780d940dff8"
            + "eaa96c3f1136bad3dc7d624cbfa8a671031ba20201c1dbfad24aa1dc66704843";
    private static final ObjectMapper JSON = new ObjectMapper();

    // NUT-07 wire form: a wallet parses the witness as JSON to check who spent the proof.
    @Test
    void storesTheNut07JsonNotToString() throws Exception {
        String stored = WitnessJson.toJson(new Witness(List.of(SIG)));
        JsonNode node = JSON.readTree(stored);
        assertEquals(SIG, node.path("signatures").get(0).asText());
        assertTrue(stored.length() <= 255, "fits the vault witness column");
    }

    @Test
    void noWitnessStoresNull() {
        assertNull(WitnessJson.toJson(null));
        assertNull(WitnessJson.toWire(null));
    }

    // Rows spent before the fix hold Lombok's rendering; they must still answer as JSON.
    @Test
    void upgradesTheLegacyLombokFormOnRead() throws Exception {
        String legacy = new Witness(List.of(SIG)).toString();
        assertTrue(legacy.startsWith("Witness(signatures=["), legacy);
        JsonNode node = JSON.readTree(WitnessJson.toWire(legacy));
        assertEquals(List.of(SIG), List.of(node.path("signatures").get(0).asText()));
    }

    @Test
    void upgradesLegacyMultiSignatureAndEmptyForms() throws Exception {
        JsonNode two = JSON.readTree(WitnessJson.toWire("Witness(signatures=[aa, bb])"));
        assertEquals(2, two.path("signatures").size());
        assertEquals("bb", two.path("signatures").get(1).asText());
        assertEquals(0, JSON.readTree(WitnessJson.toWire("Witness(signatures=null)")).path("signatures").size());
    }

    @Test
    void jsonAndUnknownFormsPassThroughUntouched() {
        String json = WitnessJson.toJson(new Witness(List.of(SIG)));
        assertEquals(json, WitnessJson.toWire(json));
        assertEquals("something-else", WitnessJson.toWire("something-else"));
        // Not hex inside the brackets: not ours to reinterpret.
        assertEquals("Witness(signatures=[zz])", WitnessJson.toWire("Witness(signatures=[zz])"));
    }
}
