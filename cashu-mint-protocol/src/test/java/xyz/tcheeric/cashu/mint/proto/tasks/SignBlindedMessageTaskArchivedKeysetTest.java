package xyz.tcheeric.cashu.mint.proto.tasks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;

/**
 * The signing task must consult the keyset's active status before it signs.
 *
 * <p>Without this the guard could be deleted from the signing path and every
 * other test would still pass — the enforcement in ADR-0004 only holds if
 * something asserts the call is actually made.
 */
@ExtendWith(MockitoExtension.class)
class SignBlindedMessageTaskArchivedKeysetTest {

    private static final ECNamedCurveParameterSpec CURVE = ECNamedCurveTable.getParameterSpec("secp256k1");
    private static final String KEYSET_ID = "abcd1234abcd1234";

    @Mock
    private MintProtocolService mintProtocolService;

    @Mock
    private SignatureVaultService signatureVaultService;

    @Test
    @DisplayName("Refuses to sign and never resolves a key when the keyset is archived")
    // Ensures an archived keyset stops issuance before any key material is touched.
    void refusesToSignWithArchivedKeyset() throws Exception {
        final Mint mint = new Mint(UUID.randomUUID().toString());
        doThrow(new CashuErrorException("{\"code\":\"keyset_inactive\"}"))
                .when(mintProtocolService).requireActiveKeySet(KEYSET_ID);

        final SignBlindedMessageTask task = new SignBlindedMessageTask(
                mint, blindedMessage(), mintProtocolService, signatureVaultService);

        assertThatThrownBy(task::execute)
                .isInstanceOf(CashuErrorException.class)
                .hasMessageContaining("keyset_inactive");

        verify(mintProtocolService, never()).getPrivateKey(any(), any(), any());
        verify(signatureVaultService, never()).store(any(), any());
    }

    @Test
    @DisplayName("Checks the keyset is active before signing")
    // Ensures the active-keyset check is on the signing path, not merely available.
    void checksKeysetIsActiveBeforeSigning() throws Exception {
        final Mint mint = new Mint(UUID.randomUUID().toString());
        when(mintProtocolService.getPrivateKey(any(), any(), eq(mint)))
                .thenReturn(PrivateKey.generateRandom());

        final SignBlindedMessageTask task = new SignBlindedMessageTask(
                mint, blindedMessage(), mintProtocolService, signatureVaultService);

        assertThat(task.execute()).isNotNull();

        verify(mintProtocolService).requireActiveKeySet(KEYSET_ID);
    }

    @Test
    @DisplayName("Checks the keyset is active in voucher mode too")
    // Vouchers derive their key from a master secret rather than the keyset, but the
    // output still claims a keyset id — so an archived keyset must not be signed
    // against by that route either.
    void refusesVoucherSigningWithArchivedKeyset() throws Exception {
        final Mint mint = new Mint(UUID.randomUUID().toString());
        doThrow(new CashuErrorException("{\"code\":\"keyset_inactive\"}"))
                .when(mintProtocolService).requireActiveKeySet(KEYSET_ID);

        final SignBlindedMessageTask task = new SignBlindedMessageTask(
                mint, blindedMessage(), mintProtocolService, signatureVaultService,
                true, "master-secret");

        assertThatThrownBy(task::execute)
                .isInstanceOf(CashuErrorException.class)
                .hasMessageContaining("keyset_inactive");
    }

    private static BlindedMessage blindedMessage() {
        return BlindedMessage.builder()
                .amount(1)
                .keySetId(KeysetId.fromString(KEYSET_ID))
                .blindedMessage(PublicKey.fromString(Hex.toHexString(CURVE.getG().getEncoded(true))))
                .build();
    }
}
