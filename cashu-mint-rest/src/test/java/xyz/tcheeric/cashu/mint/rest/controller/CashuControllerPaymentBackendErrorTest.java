package xyz.tcheeric.cashu.mint.rest.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.ResourceAccessException;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.QuoteRef;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * cashu-mint#531 review follow-up: a payment gateway I/O failure on the quote status poll used to
 * reach Tomcat, which logged Spring's "I/O error on GET request for .../findByQuoteId?quoteId=<id>"
 * at ERROR with the raw quote id. The controller now handles {@code RestClientException} itself.
 * Driven through Spring MVC so the route attributes and the exception handler are the real ones.
 */
class CashuControllerPaymentBackendErrorTest {

    private static final String QUOTE_ID = "9d745270-1405-46de-b5c5-e2762b4f5e00";

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> captured;
    private Logger rootLogger;
    private Level previousLevel;

    @BeforeEach
    void setUp() {
        CashuController<Secret> controller = new CashuController<>(
                mock(NUT06.class), mock(MintLoadService.class), mock(SignatureVaultService.class), null,
                mock(MintVaultService.class), mock(ProofVaultService.class));
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        previousLevel = rootLogger.getLevel();
        captured = new ListAppender<>();
        captured.start();
        rootLogger.addAppender(captured);
        rootLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        rootLogger.detachAppender(captured);
        rootLogger.setLevel(previousLevel);
    }

    // The gateway times out on the quote status poll. The wallet gets the NUT-00 internal error
    // body with a 500, as before, and the one ERROR line names the route and the quote ref, while
    // no log line, message or logged exception anywhere carries the raw id.
    @Test
    void aGatewayTimeoutOnTheQuotePollIsLoggedWithoutTheQuoteId() throws Exception {
        // Arrange
        ResourceAccessException timeout = new ResourceAccessException(
                "I/O error on GET request for \"http://payment-adapter/search/findByQuoteId?quoteId=" + QUOTE_ID
                        + "\": Read timed out",
                new SocketTimeoutException("Read timed out"));

        // Act
        try (MockedStatic<NUT04> nut04 = mockStatic(NUT04.class)) {
            nut04.when(() -> NUT04.quotePaymentStatus(any(), any())).thenThrow(timeout);
            mockMvc.perform(get("/v1/mint/quote/bolt11/" + QUOTE_ID))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("Payment backend unavailable"));
        }

        // Assert
        assertThat(everythingLogged()).noneMatch(text -> text.contains(QUOTE_ID));
        assertThat(captured.list).filteredOn(event -> event.getLevel() == Level.ERROR)
                .extracting(ILoggingEvent::getFormattedMessage)
                .singleElement().asString()
                .contains("payment_backend_error route=/v1/mint/quote/{method}/{quote_id}")
                .contains("quote_ref=" + QuoteRef.of(QUOTE_ID))
                .contains("findByQuoteId?quoteId=" + QuoteRef.of(QUOTE_ID));
    }

    /**
     * Every formatted message plus every message in every logged throwable chain, for the lines
     * production prints: the mint's own code at any level, everything else at INFO and above.
     * Spring's DEBUG/TRACE request logging echoes the raw path, but the mint runs
     * {@code org.springframework} at INFO, so those lines never print there.
     */
    private List<String> everythingLogged() {
        List<String> texts = new ArrayList<>();
        for (ILoggingEvent event : captured.list) {
            if (!event.getLoggerName().startsWith("xyz.tcheeric") && !event.getLevel().isGreaterOrEqual(Level.INFO)) {
                continue;
            }
            texts.add(event.getFormattedMessage());
            for (IThrowableProxy proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                texts.add(String.valueOf(proxy.getMessage()));
                for (IThrowableProxy suppressed : proxy.getSuppressed()) {
                    texts.add(String.valueOf(suppressed.getMessage()));
                }
            }
        }
        return texts;
    }
}
