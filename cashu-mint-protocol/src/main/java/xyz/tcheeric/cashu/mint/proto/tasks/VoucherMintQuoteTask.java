package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.cashu.mint.proto.util.VoucherFeeCalculator;
import xyz.tcheeric.cashu.mint.proto.util.VoucherFeeConfig;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.gateway.common.Gateway;

/**
 * Task for creating a voucher mint quote with percentage-based fee.
 *
 * <p>Unlike regular mint quotes that charge the full face value, voucher mint quotes
 * charge only a percentage of the voucher face value as a minting fee.
 *
 * <p>Example: With 10% fee configuration:
 * <ul>
 *   <li>User requests 1,000 sat voucher</li>
 *   <li>Gateway invoice is for 100 sats (10% fee)</li>
 *   <li>After payment, user receives 1,000 sat worth of tokens</li>
 * </ul>
 *
 * <p>The original face value is stored in {@link VoucherQuoteRegistry} to ensure
 * the correct amount is minted when the quote is fulfilled.
 *
 * @see VoucherFeeConfig
 * @see VoucherFeeCalculator
 * @see VoucherQuoteRegistry
 */
@Slf4j
public class VoucherMintQuoteTask implements Task<PostMintQuoteResponse> {

    private final int faceValue;
    private final PaymentMethod method;
    private final String unit;
    private final MintProtocolService mintProtocolService;

    public VoucherMintQuoteTask(int faceValue, @NonNull PaymentMethod method) {
        this(faceValue, method, null, MintProtocolServiceFactory.getInstance());
    }

    public VoucherMintQuoteTask(int faceValue,
                                @NonNull PaymentMethod method,
                                String unit,
                                @NonNull MintProtocolService mintProtocolService) {
        this.faceValue = faceValue;
        this.method = method;
        this.unit = unit;
        this.mintProtocolService = mintProtocolService;
    }

    // Backward-compatible constructor used by tests: no unit parameter
    public VoucherMintQuoteTask(int faceValue,
                                @NonNull PaymentMethod method,
                                @NonNull MintProtocolService mintProtocolService) {
        this(faceValue, method, null, mintProtocolService);
    }

    @Override
    public PostMintQuoteResponse execute() {
        log.info("Creating voucher mint quote: faceValue={}, method={}", faceValue, method);

        // Load fee percentage from configuration
        double feePercentage = VoucherFeeConfig.getFeePercentage();

        // Calculate fee-based price
        long voucherPrice = VoucherFeeCalculator.calculateFee(faceValue, feePercentage);

        log.info("Voucher mint quote: faceValue={}, feePercent={}%, chargedPrice={}, method={}",
            faceValue, feePercentage, voucherPrice, method);

        // Create gateway and call createMintQuote with fee price (not face value)
        Gateway gateway = unit == null ? mintProtocolService.createGateway(method)
                : mintProtocolService.createGateway(method, unit);

        String quoteId = gateway.createMintQuote((int) voucherPrice, null);

        // Store face value for later minting
        VoucherQuoteRegistry.storeFaceValue(quoteId, faceValue);

        // Retrieve request and expiry from gateway
        String request = gateway.getRequest(quoteId);
        Integer expiry = gateway.getPaymentExpiry(quoteId);

        log.debug("Voucher mint quote created: quoteId={}, request={}, expiry={}",
            quoteId, request, expiry);

        return PostMintQuoteResponse.builder()
                .quoteId(quoteId)
                .request(request)
                .expiry(expiry)
                .build();
    }
}
