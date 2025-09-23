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
import xyz.tcheeric.cashu.entities.rest.PostRestoreRequest;
import xyz.tcheeric.cashu.entities.rest.PostRestoreResponse;
import xyz.tcheeric.cashu.mint.proto.nut.NUT02;
import xyz.tcheeric.cashu.mint.proto.nut.NUT03;
import xyz.tcheeric.cashu.mint.proto.nut.NUT04;
import xyz.tcheeric.cashu.mint.proto.nut.NUT05;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;
import xyz.tcheeric.cashu.mint.proto.nut.NUT07;
import xyz.tcheeric.cashu.mint.proto.nut.NUT09;
import xyz.tcheeric.cashu.mint.proto.service.DefaultMintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.DefaultSignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class CashuControllerTest {

    @Test
    void handleCashuErrorReturnsStructuredError() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
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
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
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
    // generateKeySetIds removed (admin-only, non-spec)

    @Test
    void keyset() throws CashuErrorException {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
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
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
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
        // Arrange controller with a mocked loader that returns a mint owning the input keyset
        MintLoadService ml = Mockito.mock(MintLoadService.class);
        var single = new xyz.tcheeric.cashu.common.Mint(UUID.randomUUID().toString());
        xyz.tcheeric.cashu.common.KeySet ks = new xyz.tcheeric.cashu.common.KeySet();
        ks.setId("ks-1"); ks.setUnit("sat"); ks.setKeys(new xyz.tcheeric.cashu.common.Keys());
        single.addKeySet(ks);
        Mockito.when(ml.load(Mockito.eq(false))).thenReturn(java.util.List.of(single));
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), ml, new DefaultSignatureVaultService());

        // Mock request with a single input referencing ks-1
        PostSwapRequest<Secret> request = Mockito.mock(PostSwapRequest.class);
        xyz.tcheeric.cashu.common.Proof<Secret> proof = new xyz.tcheeric.cashu.common.Proof<>();
        proof.setKeySetId("ks-1");
        java.util.List<xyz.tcheeric.cashu.common.Proof<Secret>> inputs = java.util.List.of(proof);
        Mockito.when(request.getInputs()).thenReturn(inputs);

        PostSwapResponse expected = Mockito.mock(PostSwapResponse.class);
        try (MockedStatic<NUT03> mocked = Mockito.mockStatic(NUT03.class)) {
            mocked.when(() -> NUT03.swap(Mockito.any(UUID.class), Mockito.any(), Mockito.any(SignatureVaultService.class))).thenReturn(expected);
            ResponseEntity<PostSwapResponse> response = controller.swap(request);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(expected, response.getBody());
        }
    }

    @Test
    void quoteMintPost() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
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
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
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
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
        try (MockedStatic<NUT04> mocked = Mockito.mockStatic(NUT04.class)) {
            mocked.when(() -> NUT04.quotePaymentStatus("qid", PaymentMethod.MOCK)).thenReturn(null);
            ResponseEntity<PostMintQuoteResponse> response = controller.quoteMint(PaymentMethod.MOCK.name().toLowerCase(), "qid");
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNull(response.getBody());
        }
    }

    @Test
    void mintFound() throws CashuErrorException {
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
        PostMintRequest<Secret> request = Mockito.mock(PostMintRequest.class);
        PostMintResponse expected = Mockito.mock(PostMintResponse.class);
        try (MockedStatic<NUT04> mocked = Mockito.mockStatic(NUT04.class)) {
            mocked.when(() -> NUT04.mint(Mockito.any(UUID.class), Mockito.any(), Mockito.eq(PaymentMethod.MOCK), Mockito.any(SignatureVaultService.class))).thenReturn(expected);
            ResponseEntity<PostMintResponse> response = controller.mint(request, PaymentMethod.MOCK.name().toLowerCase(), UUID.randomUUID().toString());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(expected, response.getBody());
        }
    }

    @Test
    void mintNotFound() throws CashuErrorException {
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
        PostMintRequest<Secret> request = Mockito.mock(PostMintRequest.class);
        try (MockedStatic<NUT04> mocked = Mockito.mockStatic(NUT04.class)) {
            mocked.when(() -> NUT04.mint(Mockito.any(UUID.class), Mockito.any(), Mockito.eq(PaymentMethod.MOCK), Mockito.any(SignatureVaultService.class))).thenReturn(null);
            ResponseEntity<PostMintResponse> response = controller.mint(request, PaymentMethod.MOCK.name().toLowerCase(), UUID.randomUUID().toString());
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNull(response.getBody());
        }
    }

    @Test
    void quoteMeltPost() {
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
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
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
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
        CashuController<?> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
        try (MockedStatic<NUT05> mocked = Mockito.mockStatic(NUT05.class)) {
            mocked.when(() -> NUT05.quotePaymentStatus("qid", PaymentMethod.MOCK)).thenReturn(null);
            ResponseEntity<PostMeltQuoteResponse> response = controller.quoteMelt(PaymentMethod.MOCK.name().toLowerCase(), "qid");
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            assertNull(response.getBody());
        }
    }

    @Test
    void meltFound() throws CashuErrorException {
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
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
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
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
        CashuController<?> controller = new CashuController<>(nut06, new DefaultMintLoadService(), new DefaultSignatureVaultService());
        MintInfo info = new MintInfo();
        Mockito.when(nut06.mintInfo()).thenReturn(info);
        ResponseEntity<com.fasterxml.jackson.databind.node.ObjectNode> response = controller.info();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        // Compatibility fields are included
        assertNotNull(response.getBody().get("units"));
    }

    // Removed default constructor test: controller now requires injected dependencies.

    @Test
    void checkstate() throws CashuErrorException {
        // Use a loader that returns one mint so controller will call NUT07 once
        MintLoadService ml = Mockito.mock(MintLoadService.class);
        var single = new xyz.tcheeric.cashu.common.Mint(UUID.randomUUID().toString());
        Mockito.when(ml.load(Mockito.eq(false))).thenReturn(java.util.List.of(single));
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), ml, new DefaultSignatureVaultService());
        PostCheckStateRequest request = Mockito.mock(PostCheckStateRequest.class);
        PostCheckStateResponse expected = new PostCheckStateResponse();
        expected.setStates(java.util.List.of());
        try (MockedStatic<NUT07> mocked = Mockito.mockStatic(NUT07.class)) {
            mocked.when(() -> NUT07.checkState(Mockito.any(UUID.class), Mockito.eq(request))).thenReturn(expected);
            ResponseEntity<PostCheckStateResponse> response = controller.checkstate(request);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
        }
    }

    @Test
    void checkstateMergesAcrossMints() throws CashuErrorException {
        // Arrange two mints and a controller
        MintLoadService ml = Mockito.mock(MintLoadService.class);
        var m1 = new xyz.tcheeric.cashu.common.Mint(UUID.randomUUID().toString());
        var m2 = new xyz.tcheeric.cashu.common.Mint(UUID.randomUUID().toString());
        Mockito.when(ml.load(Mockito.eq(false))).thenReturn(java.util.List.of(m1, m2));
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), ml, new DefaultSignatureVaultService());

        // Build request with two keys
        var Y1 = xyz.tcheeric.cashu.common.PublicKey.fromString("02" + "0".repeat(64));
        var Y2 = xyz.tcheeric.cashu.common.PublicKey.fromString("03" + "0".repeat(64));
        PostCheckStateRequest request = new PostCheckStateRequest(java.util.List.of(Y1, Y2));

        // Mock NUT07 for each mint to return conflicting states to test merge priority (SPENT wins)
        PostCheckStateResponse r1 = new PostCheckStateResponse();
        var rs1a = new PostCheckStateResponse.ResponseState(); rs1a.setHashToCurveSecret(Y1); rs1a.setState(NUT07.UNSPENT); r1.addResponseState(rs1a);
        var rs1b = new PostCheckStateResponse.ResponseState(); rs1b.setHashToCurveSecret(Y2); rs1b.setState(NUT07.SPENT); r1.addResponseState(rs1b);

        PostCheckStateResponse r2 = new PostCheckStateResponse();
        var rs2a = new PostCheckStateResponse.ResponseState(); rs2a.setHashToCurveSecret(Y1); rs2a.setState(NUT07.SPENT); r2.addResponseState(rs2a);
        var rs2b = new PostCheckStateResponse.ResponseState(); rs2b.setHashToCurveSecret(Y2); rs2b.setState(NUT07.UNSPENT); r2.addResponseState(rs2b);

        try (MockedStatic<NUT07> mocked = Mockito.mockStatic(NUT07.class)) {
            mocked.when(() -> NUT07.checkState(Mockito.eq(UUID.fromString(m1.getId())), Mockito.eq(request)))
                  .thenReturn(r1);
            mocked.when(() -> NUT07.checkState(Mockito.eq(UUID.fromString(m2.getId())), Mockito.eq(request)))
                  .thenReturn(r2);

            ResponseEntity<PostCheckStateResponse> response = controller.checkstate(request);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertNotNull(response.getBody());
            var states = response.getBody().getStates();
            // Expect two entries, both SPENT due to merge priority
            assertEquals(2, states.size());
            java.util.Map<String, String> map = new java.util.HashMap<>();
            for (var s : states) map.put(s.getHashToCurveSecret().toString(), s.getState());
            assertEquals(NUT07.SPENT, map.get(Y1.toString()));
            assertEquals(NUT07.SPENT, map.get(Y2.toString()));
        }
    }

    /**
     * Verifies that restore endpoint delegates to NUT09 and returns its response.
     */
    @Test
    void restore() throws CashuErrorException {
        CashuController<Secret> controller = new CashuController<>(Mockito.mock(NUT06.class), new DefaultMintLoadService(), new DefaultSignatureVaultService());
        PostRestoreRequest request = Mockito.mock(PostRestoreRequest.class);
        PostRestoreResponse expected = Mockito.mock(PostRestoreResponse.class);
        try (MockedStatic<NUT09> mocked = Mockito.mockStatic(NUT09.class)) {
            mocked.when(() -> NUT09.restore(Mockito.eq(request), Mockito.any(SignatureVaultService.class))).thenReturn(expected);
            ResponseEntity<PostRestoreResponse> response = controller.restore(request);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(expected, response.getBody());
        }
    }
}
