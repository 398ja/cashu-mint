package xyz.tcheeric.cashu.mint.rest.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.tcheeric.cashu.mint.proto.domain.VoucherLifecycleState;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuote;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherQuoteRepository;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec 035 — unit tests for the provenance lookup endpoint and the
 * issuance_ratio computation policy.
 *
 * <p>Standalone MockMvc with mocked {@link VoucherQuoteRepository}; no
 * Spring context, no database. The integration test for the @Modifying
 * query on the {@link xyz.tcheeric.cashu.mint.jpa.repository.VoucherQuoteJpaRepository#recordIssuance}
 * path lives in {@code cashu-mint-rest-it}.
 */
@ExtendWith(MockitoExtension.class)
class VoucherProvenanceControllerTest {

    @Mock
    private VoucherQuoteRepository voucherQuoteRepository;

    @InjectMocks
    private VoucherProvenanceController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void returns200WithComputedRatioWhenAmountIsPositive() throws Exception {
        VoucherQuote q = stubQuote(
                "voucher-abc",
                /* faceValue */ 5000L,
                /* originalTokenAmount */ 9108L,
                VoucherLifecycleState.ISSUED);
        when(voucherQuoteRepository.findById("voucher-abc")).thenReturn(Optional.of(q));

        mockMvc.perform(get("/v1/vouchers/voucher-abc/provenance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherId").value("voucher-abc"))
                .andExpect(jsonPath("$.faceValue").value(5000))
                .andExpect(jsonPath("$.unit").value("XAF"))
                .andExpect(jsonPath("$.originalTokenAmount").value(9108))
                // 5000 / 9108 ≈ 0.5489 — leave precision to JSON; just assert > 0
                .andExpect(jsonPath("$.issuanceRatio").isNumber())
                .andExpect(jsonPath("$.lifecycleState").value("ISSUED"));
    }

    @Test
    void returns200WithNullRatioWhenAmountIsNullLegacyRow() throws Exception {
        VoucherQuote q = stubQuote(
                "voucher-legacy",
                /* faceValue */ 5000L,
                /* originalTokenAmount */ null,
                VoucherLifecycleState.ISSUED);
        when(voucherQuoteRepository.findById("voucher-legacy")).thenReturn(Optional.of(q));

        mockMvc.perform(get("/v1/vouchers/voucher-legacy/provenance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherId").value("voucher-legacy"))
                .andExpect(jsonPath("$.faceValue").value(5000))
                // Both nullable fields explicitly null — the wallet's
                // source-chain (spec 035 iter 2) already accepts null
                // and falls back to embedded face_value.
                .andExpect(jsonPath("$.originalTokenAmount").isEmpty())
                .andExpect(jsonPath("$.issuanceRatio").isEmpty());
    }

    @Test
    void returns200WithNullRatioWhenAmountIsZero() throws Exception {
        // Defensive: a zero amount would yield a non-finite or zero ratio,
        // either of which breaks the wallet's downstream multiplication.
        // The contract says null for non-positive amounts.
        VoucherQuote q = stubQuote(
                "voucher-zero",
                /* faceValue */ 5000L,
                /* originalTokenAmount */ 0L,
                VoucherLifecycleState.ISSUED);
        when(voucherQuoteRepository.findById("voucher-zero")).thenReturn(Optional.of(q));

        mockMvc.perform(get("/v1/vouchers/voucher-zero/provenance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalTokenAmount").value(0))
                .andExpect(jsonPath("$.issuanceRatio").isEmpty());
    }

    @Test
    void returns404WhenVoucherQuoteRowIsMissing() throws Exception {
        when(voucherQuoteRepository.findById(anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/vouchers/voucher-missing/provenance"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returns400WhenVoucherIdIsBlank() throws Exception {
        // Spring matches blank path-var → empty path, surfaces as 404
        // (no handler). The blank-string guard inside the handler is
        // belt-and-braces for any caller that constructs the URL with a
        // whitespace-only id. Asserting via direct method invocation.
        var response = controller.getProvenance("   ");
        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void computeIssuanceRatioReturnsNullForNullAmount() {
        assertNull(VoucherProvenanceController.computeIssuanceRatio(5000L, null));
    }

    @Test
    void computeIssuanceRatioReturnsNullForNonPositiveAmount() {
        assertNull(VoucherProvenanceController.computeIssuanceRatio(5000L, 0L));
        assertNull(VoucherProvenanceController.computeIssuanceRatio(5000L, -1L));
    }

    @Test
    void computeIssuanceRatioComputesExactDivisionForExamples() {
        // User's reproducer: 5000 XAF / 9108 sats
        Double r = VoucherProvenanceController.computeIssuanceRatio(5000L, 9108L);
        // Sanity bounds — not asserting exact double for portability;
        // checking it is in the expected range (~0.5489).
        assert r != null;
        assertEquals(0.5489, r, 0.001);

        // Same-unit voucher (face == original token amount, no partial-spend semantic)
        assertEquals(1.0, VoucherProvenanceController.computeIssuanceRatio(1000L, 1000L), 1e-12);
    }

    // ─── Helpers ──────────────────────────────────────────────────────

    private static VoucherQuote stubQuote(String quoteId,
                                          long faceValue,
                                          Long originalTokenAmount,
                                          VoucherLifecycleState lifecycle) {
        return new VoucherQuote() {
            @Override public String quoteId() { return quoteId; }
            @Override public String voucherType() { return "customer_paid"; }
            @Override public long faceValue() { return faceValue; }
            @Override public long chargedAmount() { return faceValue; }
            @Override public long fee() { return 0L; }
            @Override public Long originalTokenAmount() { return originalTokenAmount; }
            @Override public String unit() { return "XAF"; }
            @Override public String merchantId() { return null; }
            @Override public String customerId() { return null; }
            @Override public String fundingId() { return "funding-1"; }
            @Override public VoucherLifecycleState lifecycleState() { return lifecycle; }
            @Override public String idempotencyKey() { return null; }
            @Override public String requestHash() { return "hash"; }
            @Override public Instant createdAt() { return Instant.EPOCH; }
            @Override public Instant updatedAt() { return Instant.EPOCH; }
        };
    }
}
