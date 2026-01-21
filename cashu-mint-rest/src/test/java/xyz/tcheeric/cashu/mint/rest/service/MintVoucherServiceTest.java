package xyz.tcheeric.cashu.mint.rest.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherResponse;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;
import xyz.tcheeric.cashu.voucher.domain.VoucherStatus;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MintVoucherServiceTest {

    private static final String ISSUER_PRIVATE_KEY = "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String ISSUER_PUBLIC_KEY = "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798";

    @Mock
    private VoucherLedgerPort voucherLedgerPort;

    @Mock
    private VoucherBackupPort voucherBackupPort;

    private MintVoucherService mintVoucherService;

    @BeforeEach
    void setUp() {
        mintVoucherService = new MintVoucherService(
                voucherLedgerPort,
                voucherBackupPort,
                ISSUER_PRIVATE_KEY,
                ISSUER_PUBLIC_KEY
        );
    }

    // Ensures issue() returns a response with a generated cashu token payload.
    @Test
    void issue_AddsCashuTokenWhenMissing() {
        IssueVoucherRequest request = IssueVoucherRequest.builder()
                .issuerId("mint-voucher-service-test")
                .unit("sat")
                .amount(1_000L)
                .expiresInDays(30)
                .memo("token generation test")
                .build();

        IssueVoucherResponse response = mintVoucherService.issue(request);

        assertNotNull(response, "Response should be created");
        assertNotNull(response.getVoucher(), "Voucher should be present");
        assertNotNull(response.getToken(), "Token should be generated");
        assertTrue(response.getToken().startsWith("cashuA"), "Token should use cashuA prefix");

        String encodedPayload = response.getToken().substring("cashuA".length());
        String decodedPayload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        assertFalse(decodedPayload.isBlank(), "Encoded payload should decode to a voucher payload");

        verify(voucherLedgerPort).publish(any(SignedVoucher.class), eq(VoucherStatus.ISSUED));
    }
}
