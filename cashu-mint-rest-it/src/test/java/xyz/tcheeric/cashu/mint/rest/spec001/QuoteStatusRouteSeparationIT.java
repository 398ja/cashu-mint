package xyz.tcheeric.cashu.mint.rest.spec001;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.mint.jpa.entity.IssuanceRecordEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.MintQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.entity.VoucherQuoteEntity;
import xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuoteJpaRepository;
import xyz.tcheeric.cashu.mint.proto.ports.MintQuote.LifecycleState;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.rest.spec003.support.VoucherTestSupport;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cashu-mint#494 against a real PostgreSQL: the regular and voucher status routes each answer
 * only for their own quote kind, and the issuance ledger accepts a NUT-02 v2 keyset id.
 */
class QuoteStatusRouteSeparationIT extends AbstractMintDurableIT {

    /** A NUT-02 v2 keyset id: the 01 version byte plus a 32-byte hash, 66 hex characters. */
    private static final String V2_KEYSET_ID = "01" + "ef5b83".repeat(10) + "abcd";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${local.server.port}")
    int port;

    @Autowired
    VoucherQuoteJpaRepository voucherQuotes;

    private final RestTemplate restTemplate = new RestTemplate();

    private MintProtocolService originalProtocolService;

    @AfterEach
    void restoreProtocolService() {
        if (originalProtocolService != null) {
            MintProtocolServiceFactory.setInstance(originalProtocolService);
        }
    }

    // #499 on the wire: the voucher route answers with the NUT-04 fields plus charged_amount, the
    // price its invoice charges. Before payment amount_paid is 0; the price is known up front.
    @Test
    void voucherRouteReportsChargedAmountOnTheWire() throws IOException {
        String quoteId = VoucherTestSupport.newQuoteId("v499");
        VoucherQuoteEntity quote = VoucherTestSupport.unfundedQuote(quoteId, 1000L);
        quote.setChargedAmount(100L);
        quote.setFee(100L);
        voucherQuotes.saveAndFlush(quote);
        stubGateway(false);

        ResponseEntity<String> response = get("/v1/mint/quote/voucher/bolt11/" + quoteId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = MAPPER.readTree(response.getBody());
        assertThat(json.path("amount").asInt()).isEqualTo(1000);
        assertThat(json.path("amount_paid").asLong()).isZero();
        assertThat(json.path("amount_issued").asLong()).isZero();
        assertThat(json.path("charged_amount").asLong()).isEqualTo(100L);
    }

    private void stubGateway(boolean paid) {
        originalProtocolService = MintProtocolServiceFactory.getInstance();
        Gateway gateway = Mockito.mock(Gateway.class);
        Mockito.when(gateway.checkPaymentStatus(Mockito.anyString())).thenReturn(paid);
        Mockito.when(gateway.getRequest(Mockito.anyString())).thenReturn("lnbc1000n1test");
        Mockito.when(gateway.getPaymentExpiry(Mockito.anyString())).thenReturn(600);
        MintProtocolService stub = Mockito.spy(originalProtocolService);
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class));
        Mockito.doReturn(gateway).when(stub).createGateway(Mockito.any(PaymentMethod.class), Mockito.anyString());
        MintProtocolServiceFactory.setInstance(stub);
    }

    // The bypass: a voucher quote id on the regular route is not found, so nobody can read
    // "ISSUED, amount = face value" there for a quote whose invoice charged only the fee.
    @Test
    void regularRouteRefusesAVoucherQuote() throws IOException {
        String quoteId = VoucherTestSupport.newQuoteId("v494");
        voucherQuotes.saveAndFlush(VoucherTestSupport.unfundedQuote(quoteId, 1000L));

        ResponseEntity<String> response = get("/v1/mint/quote/bolt11/" + quoteId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(MAPPER.readTree(response.getBody()).path("code").asInt())
                .isEqualTo(CashuErrorCode.quote_not_found.getCode());
    }

    // The mirror: a regular quote id on the voucher route is not found either.
    @Test
    void voucherRouteRefusesARegularQuote() throws IOException {
        String quoteId = seedRegularQuote();

        ResponseEntity<String> response = get("/v1/mint/quote/voucher/bolt11/" + quoteId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(MAPPER.readTree(response.getBody()).path("code").asInt())
                .isEqualTo(CashuErrorCode.voucher_quote_not_found.getCode());
    }

    // An id neither table knows is not found, rather than answered UNPAID as if it existed.
    @Test
    void unknownQuoteIsNotFound() {
        ResponseEntity<String> response = get("/v1/mint/quote/bolt11/" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // The ledger row that closes a quote ISSUING -> ISSUED stores the keyset id. A v2 id is 66
    // characters, and the old VARCHAR(64) column refused it after the outputs were signed,
    // stranding every regular mint in ISSUING once the mint rotated onto a v2 keyset.
    @Test
    void issuanceRecordAcceptsAV2KeysetId() {
        assertThat(V2_KEYSET_ID).hasSize(66);
        String quoteId = seedRegularQuote();
        IssuanceRecordEntity record = new IssuanceRecordEntity();
        record.setQuoteId(quoteId);
        record.setOutputsHash("a".repeat(64));
        record.setSignaturesJson("[]");
        record.setKeysetId(V2_KEYSET_ID);
        record.setTotalAmount(64L);

        issuanceRecordJpaRepository.saveAndFlush(record);

        assertThat(issuanceRecordJpaRepository.findById(quoteId).orElseThrow().getKeysetId())
                .isEqualTo(V2_KEYSET_ID);
    }

    private String seedRegularQuote() {
        String quoteId = UUID.randomUUID().toString();
        MintQuoteEntity quote = new MintQuoteEntity();
        quote.setQuoteId(quoteId);
        quote.setAmount(64L);
        quote.setUnit("sat");
        quote.setMintUrl("https://mint.it.example");
        quote.setPaymentMethod("bolt11");
        quote.setInvoiceId(quoteId);
        quote.setLifecycleState(LifecycleState.ISSUING);
        quote.setRequestHash("0".repeat(64));
        mintQuoteJpaRepository.saveAndFlush(quote);
        return quoteId;
    }

    private ResponseEntity<String> get(String path) {
        try {
            return restTemplate.getForEntity("http://localhost:" + port + path, String.class);
        } catch (HttpStatusCodeException error) {
            return ResponseEntity.status(error.getStatusCode()).body(error.getResponseBodyAsString());
        }
    }
}
