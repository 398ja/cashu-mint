package xyz.tcheeric.cashu.mint.rest.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.MintIntegrityContext;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuance;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIssuanceRepository;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VoucherIssuanceRecordControllerTest {

    private static final String QUOTE = "q-1";
    private static final String VOUCHER = "6f1c2b9e-1d2a-4c3b-9f8e-7a6b5c4d3e2f";

    private VoucherQuoteRepository quotes;
    private VoucherIssuanceRepository issuances;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        quotes = mock(VoucherQuoteRepository.class);
        issuances = mock(VoucherIssuanceRepository.class);
        MintIntegrityContext.installVoucher(quotes, null, issuances, null, null, null);
        mockMvc = MockMvcBuilders.standaloneSetup(new VoucherIssuanceRecordController(quotes)).build();
    }

    @AfterEach
    void tearDown() {
        MintIntegrityContext.clear();
    }

    @Test
    void issuedBoundQuoteReturnsBindingAndOutputsHash() throws Exception {
        when(quotes.findById(QUOTE)).thenReturn(Optional.of(quote(VOUCHER, VoucherLifecycleState.ISSUED)));
        when(issuances.findByVoucherQuoteId(QUOTE)).thenReturn(Optional.of(issuance("ab".repeat(32))));

        mockMvc.perform(get("/v1/vouchers/quotes/q-1/issuance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteId").value(QUOTE))
                .andExpect(jsonPath("$.voucherId").value(VOUCHER))
                .andExpect(jsonPath("$.faceValue").value(1000))
                .andExpect(jsonPath("$.unit").value("sat"))
                .andExpect(jsonPath("$.lifecycleState").value("ISSUED"))
                .andExpect(jsonPath("$.outputsHash").value("ab".repeat(32)));
    }

    @Test
    void unmintedUnboundQuoteReportsNullsExplicitly() throws Exception {
        when(quotes.findById(QUOTE)).thenReturn(Optional.of(quote(null, VoucherLifecycleState.FUNDED)));
        when(issuances.findByVoucherQuoteId(QUOTE)).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/vouchers/quotes/q-1/issuance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherId").doesNotExist())
                .andExpect(jsonPath("$.outputsHash").doesNotExist())
                .andExpect(jsonPath("$.lifecycleState").value("FUNDED"));
    }

    @Test
    void unknownQuoteIs404() throws Exception {
        when(quotes.findById("nope")).thenReturn(Optional.empty());
        mockMvc.perform(get("/v1/vouchers/quotes/nope/issuance")).andExpect(status().isNotFound());
    }

    private static VoucherIssuance issuance(String outputsHash) {
        return new VoucherIssuance() {
            @Override public String voucherQuoteId() { return QUOTE; }
            @Override public String fundingId() { return "f-1"; }
            @Override public String issuanceId() { return QUOTE; }
            @Override public String outputsHash() { return outputsHash; }
            @Override public Instant issuedAt() { return Instant.EPOCH; }
        };
    }

    private static VoucherQuote quote(String voucherId, VoucherLifecycleState state) {
        return new VoucherQuote() {
            @Override public String quoteId() { return QUOTE; }
            @Override public String voucherType() { return "customer_paid"; }
            @Override public long faceValue() { return 1000; }
            @Override public long chargedAmount() { return 100; }
            @Override public long fee() { return 100; }
            @Override public String unit() { return "sat"; }
            @Override public String voucherId() { return voucherId; }
            @Override public String merchantId() { return null; }
            @Override public String customerId() { return null; }
            @Override public String fundingId() { return "f-1"; }
            @Override public VoucherLifecycleState lifecycleState() { return state; }
            @Override public String idempotencyKey() { return null; }
            @Override public String requestHash() { return "h"; }
            @Override public Instant createdAt() { return Instant.EPOCH; }
            @Override public Instant updatedAt() { return Instant.EPOCH; }
        };
    }
}
