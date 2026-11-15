package xyz.tcheeric.cashu.mint.rest.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import xyz.tcheeric.cashu.common.nut18.VoucherSecret;
import xyz.tcheeric.cashu.voucher.app.VoucherService;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherResponse;
import xyz.tcheeric.cashu.voucher.domain.BackingStrategy;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for VoucherController.
 *
 * <p>Tests all REST endpoints for voucher operations including:
 * - Voucher issuance (POST /v1/vouchers)
 * - Input validation
 * - Error handling
 *
 * <p>Note: This test uses standalone MockMvc setup with Mockito to test
 * the controller in isolation without requiring a full Spring context.
 * This avoids the need for Nostr infrastructure and provides fast test execution.
 */
@ExtendWith(MockitoExtension.class)
class VoucherControllerTest {

    private MockMvc mockMvc;

    private ObjectMapper objectMapper;

    @Mock
    private VoucherService voucherService;

    @InjectMocks
    private VoucherController voucherController;

    private IssueVoucherRequest validRequest;
    private IssueVoucherResponse mockResponse;

    @BeforeEach
    void setUp() {
        // Initialize MockMvc in standalone mode
        mockMvc = MockMvcBuilders.standaloneSetup(voucherController).build();

        // Initialize ObjectMapper
        objectMapper = new ObjectMapper();

        // Create valid request
        validRequest = IssueVoucherRequest.builder()
                .issuerId("merchant123")
                .unit("sat")
                .amount(10000L)
                .expiresInDays(365)
                .memo("Test voucher")
                .build();

        // Create mock response with deterministic UUID
        UUID testVoucherId = UUID.nameUUIDFromBytes("test-voucher-id".getBytes());
        VoucherSecret secret = VoucherSecret.builder()
                .voucherId(testVoucherId)
                .issuerId("merchant123")
                .unit("sat")
                .faceValue(10000L)
                .memo("Test voucher")
                .backingStrategy(BackingStrategy.MINIMAL.name())
                .issuanceRatio(1.0)
                .faceDecimals(0)
                .build();

        SignedVoucher signedVoucher = new SignedVoucher(
                secret,
                new byte[64], // Mock signature
                "mock-public-key"
        );

        mockResponse = IssueVoucherResponse.builder()
                .voucher(signedVoucher)
                .token("cashuAtest123")
                .build();
    }

    // ========== POST /v1/vouchers - Issue Voucher Tests ==========

    @Test
    void issueVoucher_Success() throws Exception {
        when(voucherService.issue(any(IssueVoucherRequest.class)))
                .thenReturn(mockResponse);

        // Expected UUID from deterministic UUID generation
        String expectedVoucherId = UUID.nameUUIDFromBytes("test-voucher-id".getBytes()).toString();

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("cashuAtest123"))
                .andExpect(jsonPath("$.voucherId").value(expectedVoucherId))
                .andExpect(jsonPath("$.amount").value(10000))
                .andExpect(jsonPath("$.unit").value("sat"));

        verify(voucherService, times(1)).issue(any(IssueVoucherRequest.class));
    }

    @Test
    void issueVoucher_MissingIssuerId() throws Exception {
        validRequest.setIssuerId(null);

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest());

        verify(voucherService, never()).issue(any());
    }

    @Test
    void issueVoucher_BlankIssuerId() throws Exception {
        validRequest.setIssuerId("   ");

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest());

        verify(voucherService, never()).issue(any());
    }

    @Test
    void issueVoucher_MissingUnit() throws Exception {
        validRequest.setUnit(null);

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest());

        verify(voucherService, never()).issue(any());
    }

    @Test
    void issueVoucher_BlankUnit() throws Exception {
        validRequest.setUnit("");

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest());

        verify(voucherService, never()).issue(any());
    }

    @Test
    void issueVoucher_MissingAmount() throws Exception {
        validRequest.setAmount(null);

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest());

        verify(voucherService, never()).issue(any());
    }

    @Test
    void issueVoucher_ZeroAmount() throws Exception {
        validRequest.setAmount(0L);

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest());

        verify(voucherService, never()).issue(any());
    }

    @Test
    void issueVoucher_NegativeAmount() throws Exception {
        validRequest.setAmount(-100L);

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest());

        verify(voucherService, never()).issue(any());
    }

    @Test
    void issueVoucher_InvalidExpiresInDays() throws Exception {
        validRequest.setExpiresInDays(-1);

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest());

        verify(voucherService, never()).issue(any());
    }

    @Test
    void issueVoucher_ZeroExpiresInDays() throws Exception {
        validRequest.setExpiresInDays(0);

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest());

        verify(voucherService, never()).issue(any());
    }

    @Test
    void issueVoucher_ServiceThrowsIllegalArgumentException() throws Exception {
        when(voucherService.issue(any(IssueVoucherRequest.class)))
                .thenThrow(new IllegalArgumentException("Invalid issuer"));

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest());

        verify(voucherService, times(1)).issue(any(IssueVoucherRequest.class));
    }

    @Test
    void issueVoucher_ServiceThrowsRuntimeException() throws Exception {
        when(voucherService.issue(any(IssueVoucherRequest.class)))
                .thenThrow(new RuntimeException("Nostr publish failed"));

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isInternalServerError());

        verify(voucherService, times(1)).issue(any(IssueVoucherRequest.class));
    }

}
