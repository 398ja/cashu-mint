package xyz.tcheeric.cashu.mint.rest.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.common.nut11.MalformedP2PKSecretException;
import xyz.tcheeric.cashu.mint.proto.error.ErrorResponse;
import xyz.tcheeric.cashu.mint.proto.service.MintLoadService;
import xyz.tcheeric.cashu.mint.proto.service.MintVaultService;
import xyz.tcheeric.cashu.mint.proto.service.ProofVaultService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.mint.proto.nut.NUT06;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * NUT-11 rejects a malformed P2PK secret as an unspendable Proof — a client error. cashu-lib
 * validates at parse time from 0.21.0 on, so these paths would otherwise surface as 500s.
 */
class CashuControllerP2PKErrorTest {

    private CashuController<Secret> controller() {
        return new CashuController<>(
                mock(NUT06.class),
                mock(MintLoadService.class),
                mock(SignatureVaultService.class),
                null,
                mock(MintVaultService.class),
                mock(ProofVaultService.class));
    }

    @Test
    void malformedP2PKSecret_isUnspendableProofNot500() {
        ResponseEntity<ErrorResponse> response = controller()
                .handleMalformedP2PKSecret(new MalformedP2PKSecretException("pubkeys[1]: bad key"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("verify_proof_failed_error", response.getBody().code());
    }

    /** Jackson rejects the lock during body binding, so it arrives wrapped — possibly several deep. */
    @Test
    void malformedP2PKSecretNestedInBodyBindingFailure_isUnwrapped() {
        Throwable nested = new IllegalStateException("jackson mapping",
                new MalformedP2PKSecretException("data: not a point on secp256k1"));
        ResponseEntity<ErrorResponse> response = controller()
                .handleUnreadableBody(new HttpMessageNotReadableException("unreadable", nested, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("verify_proof_failed_error", response.getBody().code());
    }

    /** An ordinary unreadable body stays an ordinary 400 — it is not a proof rejection. */
    @Test
    void unrelatedUnreadableBody_staysGeneric() {
        ResponseEntity<ErrorResponse> response = controller().handleUnreadableBody(
                new HttpMessageNotReadableException("truncated json", new RuntimeException("eof"), null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("internal_error", response.getBody().code());
    }
}
