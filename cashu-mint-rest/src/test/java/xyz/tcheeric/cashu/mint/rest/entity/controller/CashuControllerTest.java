package xyz.tcheeric.cashu.mint.rest.entity.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.CashuErrorException.ErrorCode;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class CashuControllerTest {

    @Test
    void handleCashuErrorReturnsStructuredError() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class));
        ErrorResponse error = new ErrorResponse("mint_invoice_not_paid_error");
        CashuErrorException ex = new CashuErrorException(error.toJson());

        ResponseEntity<ErrorResponse> response = controller.handleCashuError(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("mint_invoice_not_paid_error", response.getBody().code());
        assertEquals("Invoice not paid", response.getBody().message());
    }

    @Test
    void handleCashuErrorNotFound() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class));
        ErrorResponse error = new ErrorResponse("mint_invoice_not_paid_error");
        CashuErrorException ex = new CashuErrorException(error.toJson()) {
            @Override
            public ErrorCode getErrorCode() {
                return ErrorCode.NOT_FOUND;
            }
        };

        ResponseEntity<ErrorResponse> response = controller.handleCashuError(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("mint_invoice_not_paid_error", response.getBody().code());
        assertEquals("Invoice not paid", response.getBody().message());
    }
}
