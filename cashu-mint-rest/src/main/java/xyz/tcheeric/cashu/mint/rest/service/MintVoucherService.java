package xyz.tcheeric.cashu.mint.rest.service;

import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.voucher.app.VoucherService;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherRequest;
import xyz.tcheeric.cashu.voucher.app.dto.IssueVoucherResponse;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherBackupPort;
import xyz.tcheeric.cashu.voucher.app.ports.VoucherLedgerPort;
import xyz.tcheeric.cashu.voucher.domain.SignedVoucher;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * VoucherService decorator that enriches responses with a cashu token string.
 *
 * <p>The upstream voucher library currently returns a {@link IssueVoucherResponse}
 * without a token. This wrapper generates a deterministic token so REST clients
 * always receive the voucher payload in the expected {@code cashuA...} format.
 */
@Slf4j
public class MintVoucherService extends VoucherService {

    private static final String CASHU_TOKEN_PREFIX = "cashuA";

    public MintVoucherService(
            VoucherLedgerPort ledgerPort,
            VoucherBackupPort backupPort,
            String mintIssuerPrivateKey,
            String mintIssuerPublicKey
    ) {
        super(ledgerPort, backupPort, mintIssuerPrivateKey, mintIssuerPublicKey);
    }

    @Override
    public IssueVoucherResponse issue(IssueVoucherRequest request) {
        IssueVoucherResponse response = super.issue(request);
        attachToken(response);
        return response;
    }

    private void attachToken(IssueVoucherResponse response) {
        if (response == null) {
            return;
        }

        if (response.getToken() != null && !response.getToken().isBlank()) {
            return;
        }

        SignedVoucher voucher = response.getVoucher();
        if (voucher == null || voucher.getSecret() == null) {
            log.debug("Voucher token generation skipped because voucher or secret is missing");
            return;
        }

        String voucherJson = voucher.getSecret().toString();
        String encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(voucherJson.getBytes(StandardCharsets.UTF_8));

        response.setToken(CASHU_TOKEN_PREFIX + encoded);
    }
}
