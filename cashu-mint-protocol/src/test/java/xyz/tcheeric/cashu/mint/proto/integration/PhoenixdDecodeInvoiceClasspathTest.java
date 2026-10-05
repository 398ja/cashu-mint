package xyz.tcheeric.cashu.mint.proto.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.phoenixd.model.response.DecodeInvoiceResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the phoenixd-java version on the mint's own classpath. The mint embeds payment-adapter's
 * PhoenixdGateway, and its dependencyManagement overrides the phoenixd versions the adapter was
 * built with, so the adapter's own regression test does not cover what the mint ships.
 */
class PhoenixdDecodeInvoiceClasspathTest {

    // The in-process PhoenixdGateway decodes a RECEIVE quote's invoice and looks the payment up by
    // the hash phoenixd returns. phoenixd-java 0.3.0 dropped that hash (@JsonIgnore), so every
    // lookup failed as phoenixd_lookup_failed and the mint answered 500. The JSON is phoenixd's
    // real /decodeinvoice shape, including fields the model does not use.
    @Test
    void phoenixdsDecodedPaymentHashSurvivesDeserialisation() throws Exception {
        String json = "{\"chain\":\"mainnet\",\"amount\":30000,"
                + "\"paymentHash\":\"5a3e6c1f0f8b2d47e9a1c3b5d7f90e2a4c6e8a0b2d4f6a8c0e2a4c6e8a0b2d4f\","
                + "\"description\":\"Imani voucher\",\"minFinalCltvExpiryDelta\":18,"
                + "\"paymentSecret\":\"8c0e2a4c\",\"extraHops\":[],"
                + "\"features\":{\"activated\":{\"basic_mpp\":\"Optional\"},\"unknown\":[]},"
                + "\"timestampSeconds\":1759690000}";

        DecodeInvoiceResponse decoded = new ObjectMapper().readValue(json, DecodeInvoiceResponse.class);

        assertEquals("5a3e6c1f0f8b2d47e9a1c3b5d7f90e2a4c6e8a0b2d4f6a8c0e2a4c6e8a0b2d4f", decoded.getPaymentHash());
    }
}
