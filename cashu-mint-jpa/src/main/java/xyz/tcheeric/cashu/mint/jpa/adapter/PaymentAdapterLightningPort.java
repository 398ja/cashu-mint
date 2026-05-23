package xyz.tcheeric.cashu.mint.jpa.adapter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.mint.proto.domain.PaymentOutcome;
import xyz.tcheeric.cashu.mint.proto.ports.LightningPaymentPort;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.impl.MintProtocolServiceFactory;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.time.Duration;

/**
 * Spec 002 R4 — wraps {@code payment-adapter}'s {@link Gateway#pay(String)}
 * + {@link Gateway#checkPaymentStatus(String)} so callers in
 * {@code cashu-mint-protocol} only see a typed {@link PaymentOutcome}.
 *
 * <p>Parsing rules (strict per FR-008):
 * <ul>
 *   <li>Clean gateway success with a non-blank preimage → {@code Success}.</li>
 *   <li>Gateway returns {@code false} from {@code checkPaymentStatus} after
 *       {@code pay} returned cleanly → {@code DefinitiveFailure}.</li>
 *   <li>Any thrown exception (timeout, 5xx, parsing failure) →
 *       {@code Unknown}. The caller (saga state machine) is responsible
 *       for parking the saga in {@code PAYMENT_UNKNOWN} until reconciled.</li>
 * </ul>
 *
 * <p>The {@code timeout} parameter is currently advisory — the underlying
 * payment-adapter doesn't accept a per-call timeout today. When the
 * cross-repo {@code PaymentOutcome} return type lands (research R4
 * long-term migration), the port becomes a thin pass-through and the
 * timeout becomes operative.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cashu.mint.jpa", name = "enabled", havingValue = "true")
public class PaymentAdapterLightningPort implements LightningPaymentPort {

    private final MintProtocolService mintProtocolService;
    private final PaymentMethod method;
    private final String unit;

    /**
     * Spring-managed constructor. The melt path is single-method-per-quote
     * today; we resolve the Gateway lazily on every call so the underlying
     * {@code payment-adapter} loader can apply per-method/unit routing.
     *
     * <p>The default {@link PaymentMethod#BOLT11} matches the existing
     * deployment; future multi-method support would replace this single
     * bean with a factory keyed on (method, unit) resolved from the quote.
     */
    public PaymentAdapterLightningPort(
            @Value("${cashu.mint.melt.method:BOLT11}") String methodName,
            @Value("${cashu.mint.melt.unit:sat}") String unit) {
        this.mintProtocolService = MintProtocolServiceFactory.getInstance();
        this.method = PaymentMethod.valueOf(methodName);
        this.unit = unit;
    }

    private Gateway gateway() {
        return unit == null
                ? mintProtocolService.createGateway(method)
                : mintProtocolService.createGateway(method, unit);
    }

    @Override
    public PaymentOutcome pay(String quoteId, Duration timeout) {
        Gateway gateway;
        try {
            gateway = gateway();
        } catch (RuntimeException e) {
            return new PaymentOutcome.Unknown("gateway_resolution_threw: " + e.getClass().getSimpleName());
        }
        try {
            String response = gateway.pay(quoteId);
            return classifyAfterPay(gateway, quoteId, response);
        } catch (RuntimeException e) {
            log.warn("lightning_payment_unknown quote_id={} reason=pay_threw cause={}",
                    quoteId, e.getMessage());
            return new PaymentOutcome.Unknown("pay_threw: " + e.getClass().getSimpleName());
        }
    }

    @Override
    public PaymentOutcome checkStatus(String quoteId) {
        Gateway gateway;
        try {
            gateway = gateway();
        } catch (RuntimeException e) {
            return new PaymentOutcome.Unknown("gateway_resolution_threw: " + e.getClass().getSimpleName());
        }
        try {
            boolean paid = gateway.checkPaymentStatus(quoteId);
            if (paid) {
                return success(gateway, quoteId);
            }
            // checkPaymentStatus=false isn't itself a definitive failure —
            // it could mean "not yet observed". Surface as Unknown.
            return new PaymentOutcome.Unknown("provider_status_pending");
        } catch (RuntimeException e) {
            return new PaymentOutcome.Unknown("status_threw: " + e.getClass().getSimpleName());
        }
    }

    private PaymentOutcome classifyAfterPay(Gateway gateway, String quoteId, String payResponse) {
        try {
            boolean paid = gateway.checkPaymentStatus(quoteId);
            if (paid) {
                return success(gateway, quoteId);
            }
            // pay returned cleanly but the gateway doesn't acknowledge the
            // payment yet. Treat as ambiguous, not definitive failure: a
            // provider that needs a few seconds to settle is the most common
            // case (FR-008 strict-parse).
            return new PaymentOutcome.Unknown("status_not_paid_after_pay");
        } catch (RuntimeException e) {
            return new PaymentOutcome.Unknown("status_check_threw: " + e.getClass().getSimpleName());
        }
    }

    private PaymentOutcome success(Gateway gateway, String quoteId) {
        String preimage;
        try {
            preimage = gateway.getPaymentPreimage(quoteId);
        } catch (RuntimeException e) {
            return new PaymentOutcome.Unknown("preimage_lookup_threw: " + e.getClass().getSimpleName());
        }
        if (preimage == null || preimage.isBlank()) {
            return new PaymentOutcome.Unknown("missing_preimage");
        }
        Integer amount;
        try {
            amount = gateway.getAmount(quoteId);
        } catch (RuntimeException e) {
            amount = null;
        }
        long amountSettled = amount == null ? 0L : amount.longValue();
        return new PaymentOutcome.Success(preimage, amountSettled, 0L, preimage);
    }

}
