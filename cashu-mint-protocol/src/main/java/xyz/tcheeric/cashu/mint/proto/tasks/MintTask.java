package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.SplittingService;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.QuoteLockManager;
import xyz.tcheeric.cashu.mint.proto.util.VoucherQuoteRegistry;
import xyz.tcheeric.gateway.common.Gateway;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

// TEST - When mint_invoice_not_paid_error is thrown, signBlindedMessage is never invoked, else it is invoked for each blindedMessage in the request
@Slf4j
public class MintTask<T extends Secret> extends InstrumentedTask<PostMintResponse> {
    private final PostMintRequest<T> postMintRequest;
    private final PaymentMethod method;
    private final String unit;
    private final Mint mint;
    private final MintProtocolService mintProtocolService;
    private final SignatureVaultService signatureVaultService;
    private final SplittingService splittingService = new SplittingService();


    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService) {
        this(postMintRequest, method, null, mint, mintProtocolService, signatureVaultService);
    }

    public MintTask(@NonNull PostMintRequest<T> postMintRequest,
                    @NonNull PaymentMethod method,
                    String unit,
                    @NonNull Mint mint,
                    @NonNull MintProtocolService mintProtocolService,
                    @NonNull SignatureVaultService signatureVaultService) {
        this.postMintRequest = postMintRequest;
        this.method = method;
        this.unit = unit;
        this.mint = mint;
        this.mintProtocolService = mintProtocolService;
        this.signatureVaultService = signatureVaultService;
    }

    @Override
    protected PostMintResponse doExecute() throws CashuErrorException {
        PostMintResponse result = new PostMintResponse();
        List<BlindedMessage> blindedMessages = Objects.requireNonNull(
                postMintRequest.getBlindedMessages(),
                "Blinded messages must not be null");

        // If the invoice was not paid yet, Bob responds with a structured error.
        if (log.isDebugEnabled()) {
            log.debug("Starting mint task: method={} unit={} blindedMessages={}", method, unit,
                    blindedMessages.size());
        }

        String quoteId = postMintRequest.getQuoteId();

        // Per-quote lock: serializes concurrent requests for the same quote
        // to prevent double-mint attacks while allowing parallel minting of different quotes
        try (QuoteLockManager.QuoteLock quoteLock = QuoteLockManager.lockQuote(quoteId)) {

            // Voucher tokens use mock payment - no real bitcoin backing needed
            boolean isVoucherQuote = VoucherQuoteRegistry.isVoucherQuote(quoteId);

            if (isVoucherQuote) {
                // Vouchers are merchant IOUs - skip payment verification
                log.info("mint_task voucher_quote_detected quote_id={} mock_payment=true", quoteId);
            } else {
                // Regular tokens require real Lightning payment per NUT-04
                Gateway gateway = unit == null ? mintProtocolService.createGateway(method)
                        : mintProtocolService.createGateway(method, unit);
                boolean paid = gateway.checkPaymentStatus(quoteId);
                if (log.isDebugEnabled()) {
                    log.debug("Payment status for quoteId={} paid={}", quoteId, paid);
                }
                if (!paid) {
                    ErrorResponse error = new ErrorResponse("mint_invoice_not_paid_error");
                    throw new CashuErrorException(error.toJson());
                }
            }

            // Check if this is a voucher quote and validate against face value
            Long voucherFaceValue = VoucherQuoteRegistry.getFaceValue(quoteId);
            if (voucherFaceValue != null) {
                // This is a voucher quote - validate total amount matches face value
                long totalBlindedAmount = blindedMessages.stream()
                        .mapToLong(BlindedMessage::getAmount)
                        .sum();

                if (totalBlindedAmount != voucherFaceValue) {
                    log.error("Voucher mint amount mismatch: quoteId={} expected={} actual={}",
                            quoteId, voucherFaceValue, totalBlindedAmount);
                    VoucherQuoteRegistry.removeFaceValue(quoteId); // Clean up on error
                    ErrorResponse error = new ErrorResponse("mint_amount_mismatch");
                    throw new CashuErrorException(error.toJson());
                }

                log.info("Voucher mint validated: quoteId={} faceValue={}", quoteId, voucherFaceValue);
            }

            // Vouchers allow arbitrary denominations (free splitting)
            // Regular tokens require power-of-2 denominations per NUT-00
            if (isVoucherQuote) {
                log.info("mint_task voucher_quote amount={} arbitrary_denominations=true",
                        blindedMessages.stream().mapToLong(BlindedMessage::getAmount).sum());
            } else {
                validateDenominations(blindedMessages, mint);
            }

            log.debug("Signing {} blinded messages...", blindedMessages.size());

            // Use standard keyset keys for both vouchers and regular tokens
            for (BlindedMessage bm : blindedMessages) {
                SignBlindedMessageTask signBlindedMessageTask = new SignBlindedMessageTask(
                        mint, bm, mintProtocolService, signatureVaultService);
                BlindSignature bSignature = signBlindedMessageTask.execute();
                result.addBlindSignature(bSignature);
                if (log.isDebugEnabled()) {
                    log.debug("Signed blinded message amount={} keySetId={}", bm.getAmount(), bm.getKeySetId());
                }
            }

            // Clean up voucher quote registry after successful minting
            if (voucherFaceValue != null) {
                VoucherQuoteRegistry.removeFaceValue(quoteId);
                log.debug("Cleaned up voucher quote from registry: quoteId={}", quoteId);
            }

            return result;
        } // QuoteLock auto-released here
    }

    private void validateDenominations(List<BlindedMessage> blindedMessages, Mint mint) throws CashuErrorException {
        if (blindedMessages == null || blindedMessages.isEmpty()) {
            throw new CashuErrorException("mint_request_missing_outputs");
        }

        Map<String, List<Integer>> outputsByKeyset = new HashMap<>();
        for (BlindedMessage message : blindedMessages) {
            if (message == null) {
                throw new CashuErrorException("mint_request_contains_null_output");
            }
            if (message.getKeySetId() == null) {
                throw new CashuErrorException("missing_keyset_id");
            }
            int amount = message.getAmount();
            if (amount <= 0) {
                throw new CashuErrorException("invalid_output_amount");
            }
            outputsByKeyset
                    .computeIfAbsent(message.getKeySetId().toString(), ignored -> new ArrayList<>())
                    .add(amount);
        }

        for (var entry : outputsByKeyset.entrySet()) {
            String keysetId = entry.getKey();
            KeySet keySet = findKeySet(mint, keysetId);
            Set<Integer> availableDenoms = keySet.getKeys() == null
                    ? Set.of()
                    : keySet.getKeys().getValues().keySet().stream()
                    .map(BigInteger::intValue)
                    .filter(value -> value > 0)
                    .collect(Collectors.toSet());
            long total = entry.getValue().stream().mapToLong(Integer::longValue).sum();
            List<Integer> expected;
            try {
                expected = splittingService.split(total, availableDenoms);
            } catch (IllegalStateException e) {
                throw new CashuErrorException("invalid_denominations");
            }
            List<Integer> actual = new ArrayList<>(entry.getValue());
            actual.sort(Comparator.reverseOrder());
            if (!actual.equals(expected)) {
                throw new CashuErrorException("invalid_denominations");
            }
        }
    }

    private KeySet findKeySet(Mint mint, String keysetId) throws CashuErrorException {
        return mint.getKeySets().stream()
                .filter(keySet -> keysetId.equals(keySet.getId()))
                .findFirst()
                .orElseThrow(() -> new CashuErrorException("keyset_not_found"));
    }
}
