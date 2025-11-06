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
import xyz.tcheeric.cashu.voucher.app.VoucherService;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherResponse;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherSecret;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for VoucherController.
 *
 * <p>Tests all REST endpoints for voucher operations including:
 * - Voucher issuance (POST /v1/vouchers)
 * - Voucher status queries (GET /v1/vouchers/{id}/status)
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

        // Create mock response
        VoucherSecret secret = VoucherSecret.create(
                "test-voucher-id",
                "merchant123",
                "sat",
                10000L,
                null,
                "Test voucher"
        );

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

        mockMvc.perform(post("/v1/vouchers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("cashuAtest123"))
                .andExpect(jsonPath("$.voucherId").value("test-voucher-id"))
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

    // ========== GET /v1/vouchers/{voucherId}/status - Status Query Tests ==========

    @Test
    void getVoucherStatus_Found() throws Exception {
        when(voucherService.queryStatus("test-voucher-id"))
                .thenReturn(Optional.of(VoucherStatus.ISSUED));

        mockMvc.perform(get("/v1/vouchers/test-voucher-id/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherId").value("test-voucher-id"))
                .andExpect(jsonPath("$.status").value("ISSUED"));

        verify(voucherService, times(1)).queryStatus("test-voucher-id");
    }

    @Test
    void getVoucherStatus_NotFound() throws Exception {
        when(voucherService.queryStatus("non-existent-id"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/vouchers/non-existent-id/status"))
                .andExpect(status().isNotFound());

        verify(voucherService, times(1)).queryStatus("non-existent-id");
    }

    @Test
    void getVoucherStatus_Redeemed() throws Exception {
        when(voucherService.queryStatus("redeemed-voucher"))
                .thenReturn(Optional.of(VoucherStatus.REDEEMED));

        mockMvc.perform(get("/v1/vouchers/redeemed-voucher/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherId").value("redeemed-voucher"))
                .andExpect(jsonPath("$.status").value("REDEEMED"));

        verify(voucherService, times(1)).queryStatus("redeemed-voucher");
    }

    @Test
    void getVoucherStatus_Revoked() throws Exception {
        when(voucherService.queryStatus("revoked-voucher"))
                .thenReturn(Optional.of(VoucherStatus.REVOKED));

        mockMvc.perform(get("/v1/vouchers/revoked-voucher/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        verify(voucherService, times(1)).queryStatus("revoked-voucher");
    }

    @Test
    void getVoucherStatus_Expired() throws Exception {
        when(voucherService.queryStatus("expired-voucher"))
                .thenReturn(Optional.of(VoucherStatus.EXPIRED));

        mockMvc.perform(get("/v1/vouchers/expired-voucher/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));

        verify(voucherService, times(1)).queryStatus("expired-voucher");
    }

    @Test
    void getVoucherStatus_ServiceThrowsException() throws Exception {
        when(voucherService.queryStatus(anyString()))
                .thenThrow(new RuntimeException("Nostr query failed"));

        mockMvc.perform(get("/v1/vouchers/test-id/status"))
                .andExpect(status().isInternalServerError());

        verify(voucherService, times(1)).queryStatus("test-id");
    }
}
