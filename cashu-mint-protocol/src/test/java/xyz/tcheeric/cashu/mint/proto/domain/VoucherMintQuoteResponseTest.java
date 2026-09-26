package xyz.tcheeric.cashu.mint.proto.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import xyz.tcheeric.cashu.entities.rest.nut04.PostMintQuoteResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The voucher status response on the wire: NUT-04 accounting describes the entitlement, and the
 * price travels as {@code charged_amount} (cashu-mint#499).
 */
class VoucherMintQuoteResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static VoucherMintQuoteResponse issuedVoucher() {
        PostMintQuoteResponse quote = PostMintQuoteResponse.builder()
                .quoteId("vq-1")
                .request("lnbc1000n1...")
                .amount(1000)
                .unit("sat")
                .state("ISSUED")
                .paid(true)
                .amountPaid(1000L)
                .amountIssued(1000L)
                .updatedAt(1_790_000_000L)
                .expiry(1_790_000_060)
                .build();
        return new VoucherMintQuoteResponse(quote, 100L);
    }

    // A wallet reading the JSON gets every NUT-04 field of the quote it was built from, plus the
    // charge under charged_amount.
    @Test
    void serialisesTheNut04FieldsAndTheCharge() throws Exception {
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(issuedVoucher()));

        assertThat(json.path("quote").asText()).isEqualTo("vq-1");
        assertThat(json.path("amount").asInt()).isEqualTo(1000);
        assertThat(json.path("amount_paid").asLong()).isEqualTo(1000L);
        assertThat(json.path("amount_issued").asLong()).isEqualTo(1000L);
        assertThat(json.path("updated_at").asLong()).isEqualTo(1_790_000_000L);
        assertThat(json.path("state").asText()).isEqualTo("ISSUED");
        assertThat(json.path("expiry").asInt()).isEqualTo(1_790_000_060);
        assertThat(json.path("charged_amount").asLong()).isEqualTo(100L);
    }

    // A client that only knows NUT-04 must still read the response: PostMintQuoteResponse ignores
    // unknown fields, so charged_amount is simply skipped.
    @Test
    void aPlainNut04ClientStillReadsIt() throws Exception {
        String json = MAPPER.writeValueAsString(issuedVoucher());

        PostMintQuoteResponse plain = MAPPER.readValue(json, PostMintQuoteResponse.class);

        assertThat(plain.getAmountPaid()).isEqualTo(1000L);
        assertThat(plain.getAmountIssued()).isEqualTo(1000L);
    }
}
