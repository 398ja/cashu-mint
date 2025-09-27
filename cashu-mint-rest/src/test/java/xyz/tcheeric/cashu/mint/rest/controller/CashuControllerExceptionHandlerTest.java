package xyz.tcheeric.cashu.mint.rest.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.HttpClientErrorException;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class CashuControllerExceptionHandlerTest {

    private final CashuController<Secret> controller = new CashuController<>(
            mock(NUT06.class),
            mock(MintLoadService.class),
            mock(SignatureVaultService.class)
    );

    // Ensures payment gateway 404 responses map to a 402 payment required error payload for known methods.
    @Test
    void handleGatewayNotFoundShouldMapPaymentGatewayRequestsToPaymentRequired() {
        String methodSegment = PaymentMethod.BOLT11.name().toLowerCase(Locale.ROOT);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/mint/quote/" + methodSegment + "/123");
        HttpClientErrorException.NotFound exception = createNotFoundException();

        ResponseEntity<ErrorResponse> response = controller.handleGatewayNotFound(exception, request);

        assertEquals(HttpStatus.PAYMENT_REQUIRED, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("mint_invoice_not_paid_error", body.code());
    }

    // Ensures non-gateway 404 responses propagate so other handlers can translate them.
    @Test
    void handleGatewayNotFoundShouldRethrowForNonLightningRequests() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/keys/keyset/abc");
        HttpClientErrorException.NotFound exception = createNotFoundException();

        assertThrows(HttpClientErrorException.NotFound.class, () -> controller.handleGatewayNotFound(exception, request));
    }

    private HttpClientErrorException.NotFound createNotFoundException() {
        return (HttpClientErrorException.NotFound) HttpClientErrorException.create(
                HttpStatus.NOT_FOUND,
                "Not Found",
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8
        );
    }
}
