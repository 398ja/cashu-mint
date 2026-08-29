package xyz.tcheeric.cashu.mint.proto.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import xyz.tcheeric.cashu.common.nut00.CashuErrorCode;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.nut05.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.util.AmountLimitContext;
import xyz.tcheeric.cashu.mint.proto.util.AmountLimitPolicy;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties;
import xyz.tcheeric.cashu.mint.proto.util.MintCapabilityProperties.PaymentMethodLimits;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #390 — the quote paths must refuse what {@code /v1/info} says the mint
 * will not do.
 *
 * <p>{@link xyz.tcheeric.cashu.mint.proto.util.AmountLimitPolicyTest} pins the
 * policy itself; these tests pin that the mint and melt quote tasks actually
 * consult it, which is the half that was missing when the limits were only ever
 * read by the info endpoint.
 */
class QuoteAmountLimitEnforcementTest {

    private static final String UNIT = "sat";
    private static final int MAX_AMOUNT = 500;
    private static final String QUOTE_ID = "qid";

    @BeforeEach
    void installNarrowLimits() {
        MintCapabilityProperties capabilities = new MintCapabilityProperties();
        capabilities.setMintMethods(List.of(limits()));
        capabilities.setMeltMethods(List.of(limits()));
        AmountLimitContext.install(new AmountLimitPolicy(capabilities));
    }

    @AfterEach
    void restoreDefaults() {
        AmountLimitContext.clear();
    }

    // A mint quote above the advertised max_amount is refused with the typed
    // code, and the gateway is never asked for an invoice the mint would not honour.
    @Test
    void mintQuoteAboveTheAdvertisedMaximumIsRefusedBeforeReachingTheGateway() {
        Gateway gateway = Mockito.mock(Gateway.class);
        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        MintQuoteTask task = new MintQuoteTask(MAX_AMOUNT + 1L, PaymentMethod.MOCK, service);

        assertThatThrownBy(task::execute)
                .isInstanceOf(CashuErrorException.class)
                .extracting(error -> ((CashuErrorException) error).getErrorCode())
                .isEqualTo(CashuErrorCode.amount_outside_limit_range);
        verify(gateway, never()).createMintQuote(anyInt(), Mockito.any());
    }

    // A mint quote at exactly the advertised max_amount still succeeds, so the
    // enforcement matches the advertised bound rather than sitting inside it.
    @Test
    void mintQuoteAtTheAdvertisedMaximumIsAccepted() {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMintQuote(anyInt(), Mockito.isNull())).thenReturn(QUOTE_ID);
        when(gateway.getRequest(QUOTE_ID)).thenReturn("req");
        when(gateway.getPaymentExpiry(QUOTE_ID)).thenReturn(123);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        MintQuoteTask task = new MintQuoteTask((long) MAX_AMOUNT, PaymentMethod.MOCK, service);

        assertThatCode(task::execute).doesNotThrowAnyException();
    }

    // A melt quote whose decoded invoice exceeds the advertised max_amount is
    // refused rather than quoted, so the mint never promises a payment it
    // advertised it would not make.
    @Test
    void meltQuoteAboveTheAdvertisedMaximumIsRefused() {
        Gateway gateway = Mockito.mock(Gateway.class);
        when(gateway.createMeltQuote(Mockito.any())).thenReturn(QUOTE_ID);
        when(gateway.getFeeReserve(QUOTE_ID)).thenReturn(0);
        when(gateway.getPaymentExpiry(QUOTE_ID)).thenReturn(123);
        when(gateway.getAmount(QUOTE_ID)).thenReturn(MAX_AMOUNT + 1);

        MintProtocolService service = Mockito.mock(MintProtocolService.class);
        when(service.createGateway(PaymentMethod.MOCK)).thenReturn(gateway);

        PostMeltQuoteRequest request = new PostMeltQuoteRequest();
        request.setRequest("lnbc1");
        MeltQuoteTask task = new MeltQuoteTask(request, PaymentMethod.MOCK, service);

        assertThatThrownBy(task::execute)
                .isInstanceOf(CashuErrorException.class)
                .extracting(error -> ((CashuErrorException) error).getErrorCode())
                .isEqualTo(CashuErrorCode.amount_outside_limit_range);
    }

    private PaymentMethodLimits limits() {
        PaymentMethodLimits limits = new PaymentMethodLimits();
        limits.setMethod("bolt11");
        limits.setUnit(UNIT);
        limits.setMinAmount(1);
        limits.setMaxAmount(MAX_AMOUNT);
        return limits;
    }
}
