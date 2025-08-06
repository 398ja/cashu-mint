package xyz.tcheeric.cashu.mint.rest.entity.controller;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import xyz.tcheeric.cashu.common.ActiveKeySet;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.entities.rest.ActiveKeySetResponse;
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
import xyz.tcheeric.cashu.entities.rest.KeySetResponse;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateRequest;
import xyz.tcheeric.cashu.entities.rest.PostCheckStateResponse;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.PostMeltRequest;
import xyz.tcheeric.cashu.entities.rest.PostMeltResponse;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintQuoteResponse;
import xyz.tcheeric.cashu.entities.rest.PostMintRequest;
import xyz.tcheeric.cashu.entities.rest.PostMintResponse;
import xyz.tcheeric.cashu.entities.rest.PostSwapRequest;
import xyz.tcheeric.cashu.entities.rest.PostSwapResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import xyz.tcheeric.cashu.mint.proto.nut.NUT03;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT05;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CashuControllerTest {

    @Test
    void handleCashuErrorReturnsStructuredError() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
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
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        ErrorResponse error = new ErrorResponse("mint_invoice_not_paid_error");
        CashuErrorException ex = new CashuErrorException(error.toJson()) {
            @Override
            public String getMessage() {
                return "not found";
            }
        };

        ResponseEntity<ErrorResponse> response = controller.handleCashuError(ex);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("mint_invoice_not_paid_error", response.getBody().code());
        assertEquals("Invoice not paid", response.getBody().message());
    }

    @Test
    void generateKeySetIds() throws CashuErrorException {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        KeySet keySet = Mockito.mock(KeySet.class);
        try (MockedStatic<NUT02> mocked = Mockito.mockStatic(NUT02.class)) {
            mocked.when(() -> NUT02.keys(Mockito.any(UUID.class))).thenReturn(java.util.List.of(keySet));
            ResponseEntity<KeySetResponse> response = controller.generateKeySetIds(UUID.randomUUID().toString());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(new KeySetResponse(java.util.List.of(keySet)), response.getBody());
        }
    }

    @Test
    void keyset() throws CashuErrorException {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        KeySet keySet = Mockito.mock(KeySet.class);
        try (MockedStatic<NUT02> mocked = Mockito.mockStatic(NUT02.class)) {
            mocked.when(() -> NUT02.keys(Mockito.anyString(), Mockito.any(MintLoadService.class))).thenReturn(keySet);
            ResponseEntity<KeySetResponse> response = controller.keyset("kid");
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(new KeySetResponse(java.util.List.of(keySet)), response.getBody());
        }
    }

    @Test
    void keysets() throws CashuErrorException {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        ActiveKeySet active = Mockito.mock(ActiveKeySet.class);
        try (MockedStatic<NUT02> mocked = Mockito.mockStatic(NUT02.class)) {
            mocked.when(() -> NUT02.activeKeySets(Mockito.any(MintLoadService.class))).thenReturn(java.util.List.of(active));
            ResponseEntity<ActiveKeySetResponse> response = controller.keysets();
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(new ActiveKeySetResponse(java.util.List.of(active)), response.getBody());
        }
    }

    @Test
    void swap() throws CashuErrorException {
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        PostSwapRequest<Secret> request = Mockito.mock(PostSwapRequest.class);
        PostSwapResponse expected = Mockito.mock(PostSwapResponse.class);
        try (MockedStatic<NUT03> mocked = Mockito.mockStatic(NUT03.class)) {
            mocked.when(() -> NUT03.swap(Mockito.any(UUID.class), Mockito.any())).thenReturn(expected);
            ResponseEntity<PostSwapResponse> response = controller.swap(UUID.randomUUID().toString(), request);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(expected, response.getBody());
        }
    }

    @Test
    void quoteMintPost() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        PostMintQuoteRequest request = Mockito.mock(PostMintQuoteRequest.class);
        Mockito.when(request.getAmount()).thenReturn(1);
        PostMintQuoteResponse expected = Mockito.mock(PostMintQuoteResponse.class);
        try (MockedStatic<NUT04> mocked = Mockito.mockStatic(NUT04.class)) {
            mocked.when(() -> NUT04.quote(1, PaymentMethod.MOCK)).thenReturn(expected);
            ResponseEntity<PostMintQuoteResponse> response = controller.quoteMint(request, PaymentMethod.MOCK.name().toLowerCase());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(expected, response.getBody());
        }
    }

    @Test
    void quoteMintGetFound() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        PostMintQuoteResponse expected = Mockito.mock(PostMintQuoteResponse.class);
        try (MockedStatic<NUT04> mocked = Mockito.mockStatic(NUT04.class)) {
            mocked.when(() -> NUT04.quotePaymentStatus("qid", PaymentMethod.MOCK)).thenReturn(expected);
            ResponseEntity<PostMintQuoteResponse> response = controller.quoteMint(PaymentMethod.MOCK.name().toLowerCase(), "qid");
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(expected, response.getBody());
        }
    }

    @Test
    void quoteMintGetNotFound() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        try (MockedStatic<NUT04> mocked = Mockito.mockStatic(NUT04.class)) {
            mocked.when(() -> NUT04.quotePaymentStatus("qid", PaymentMethod.MOCK)).thenReturn(null);
            ResponseEntity<PostMintQuoteResponse> response = controller.quoteMint(PaymentMethod.MOCK.name().toLowerCase(), "qid");
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNull(response.getBody());
        }
    }

    @Test
    void mintFound() throws CashuErrorException {
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        PostMintRequest<Secret> request = Mockito.mock(PostMintRequest.class);
        PostMintResponse expected = Mockito.mock(PostMintResponse.class);
        try (MockedStatic<NUT04> mocked = Mockito.mockStatic(NUT04.class)) {
            mocked.when(() -> NUT04.mint(Mockito.any(UUID.class), Mockito.any(), Mockito.eq(PaymentMethod.MOCK))).thenReturn(expected);
            ResponseEntity<PostMintResponse> response = controller.mint(request, PaymentMethod.MOCK.name().toLowerCase(), UUID.randomUUID().toString());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(expected, response.getBody());
        }
    }

    @Test
    void mintNotFound() throws CashuErrorException {
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        PostMintRequest<Secret> request = Mockito.mock(PostMintRequest.class);
        try (MockedStatic<NUT04> mocked = Mockito.mockStatic(NUT04.class)) {
            mocked.when(() -> NUT04.mint(Mockito.any(UUID.class), Mockito.any(), Mockito.eq(PaymentMethod.MOCK))).thenReturn(null);
            ResponseEntity<PostMintResponse> response = controller.mint(request, PaymentMethod.MOCK.name().toLowerCase(), UUID.randomUUID().toString());
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNull(response.getBody());
        }
    }

    @Test
    void quoteMeltPost() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        PostMeltQuoteRequest request = Mockito.mock(PostMeltQuoteRequest.class);
        PostMeltQuoteResponse expected = Mockito.mock(PostMeltQuoteResponse.class);
        try (MockedStatic<NUT05> mocked = Mockito.mockStatic(NUT05.class)) {
            mocked.when(() -> NUT05.quote(request, PaymentMethod.MOCK)).thenReturn(expected);
            ResponseEntity<PostMeltQuoteResponse> response = controller.quoteMelt(request, PaymentMethod.MOCK.name().toLowerCase());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(expected, response.getBody());
        }
    }

    @Test
    void quoteMeltGetFound() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        PostMeltQuoteResponse expected = Mockito.mock(PostMeltQuoteResponse.class);
        try (MockedStatic<NUT05> mocked = Mockito.mockStatic(NUT05.class)) {
            mocked.when(() -> NUT05.quotePaymentStatus("qid", PaymentMethod.MOCK)).thenReturn(expected);
            ResponseEntity<PostMeltQuoteResponse> response = controller.quoteMelt(PaymentMethod.MOCK.name().toLowerCase(), "qid");
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(expected, response.getBody());
        }
    }

    @Test
    void quoteMeltGetNotFound() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        try (MockedStatic<NUT05> mocked = Mockito.mockStatic(NUT05.class)) {
            mocked.when(() -> NUT05.quotePaymentStatus("qid", PaymentMethod.MOCK)).thenReturn(null);
            ResponseEntity<PostMeltQuoteResponse> response = controller.quoteMelt(PaymentMethod.MOCK.name().toLowerCase(), "qid");
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNull(response.getBody());
        }
    }

    @Test
    void meltFound() throws CashuErrorException {
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        PostMeltRequest<Secret> request = Mockito.mock(PostMeltRequest.class);
        PostMeltResponse expected = Mockito.mock(PostMeltResponse.class);
        try (MockedStatic<NUT05> mocked = Mockito.mockStatic(NUT05.class)) {
            mocked.when(() -> NUT05.melt(Mockito.any(UUID.class), Mockito.any(), Mockito.eq(PaymentMethod.MOCK))).thenReturn(expected);
            ResponseEntity<PostMeltResponse> response = controller.melt(request, PaymentMethod.MOCK.name().toLowerCase(), UUID.randomUUID().toString());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(expected, response.getBody());
        }
    }

    @Test
    void meltNotFound() throws CashuErrorException {
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        PostMeltRequest<Secret> request = Mockito.mock(PostMeltRequest.class);
        try (MockedStatic<NUT05> mocked = Mockito.mockStatic(NUT05.class)) {
            mocked.when(() -> NUT05.melt(Mockito.any(UUID.class), Mockito.any(), Mockito.eq(PaymentMethod.MOCK))).thenReturn(null);
            ResponseEntity<PostMeltResponse> response = controller.melt(request, PaymentMethod.MOCK.name().toLowerCase(), UUID.randomUUID().toString());
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNull(response.getBody());
        }
    }

    @Test
    void info() {
        NUT06 nut06 = Mockito.mock(NUT06.class);
        CashuController<?> controller = new CashuController<>(nut06, new DefaultMintLoadService());
        MintInfo info = new MintInfo();
        Mockito.when(nut06.mintInfo()).thenReturn(info);
        ResponseEntity<MintInfo> response = controller.info();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(info, response.getBody());
    }

    @Test
    void defaultConstructorInfo() {
        CashuController<Secret> controller = new CashuController<>();
        ResponseEntity<MintInfo> response = controller.info();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void checkstate() throws CashuErrorException {
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService());
        PostCheckStateRequest request = Mockito.mock(PostCheckStateRequest.class);
        PostCheckStateResponse expected = Mockito.mock(PostCheckStateResponse.class);
        try (MockedStatic<NUT07> mocked = Mockito.mockStatic(NUT07.class)) {
            mocked.when(() -> NUT07.checkState(Mockito.any(UUID.class), Mockito.eq(request))).thenReturn(expected);
            ResponseEntity<PostCheckStateResponse> response = controller.checkstate(request, UUID.randomUUID().toString());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(expected, response.getBody());
        }
    }
}
