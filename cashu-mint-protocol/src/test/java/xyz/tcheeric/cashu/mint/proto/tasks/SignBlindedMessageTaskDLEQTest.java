package xyz.tcheeric.cashu.mint.proto.tasks;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECNamedCurveParameterSpec;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.nut12.DLEQProof;
import xyz.tcheeric.cashu.common.KeysetId;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.PublicKey;
import xyz.tcheeric.cashu.mint.proto.service.DLEQProofGenerator;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.service.SignatureVaultService;
import xyz.tcheeric.cashu.common.util.CashuErrorException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignBlindedMessageTaskDLEQTest {

    private static final ECNamedCurveParameterSpec CURVE = ECNamedCurveTable.getParameterSpec("secp256k1");

    @Mock
    private MintProtocolService mintProtocolService;

    @Mock
    private SignatureVaultService signatureVaultService;

    @Mock
    private DLEQProofGenerator dleqProofGenerator;

    @Test
    @DisplayName("Attaches DLEQ proof to blind signature when generator succeeds")
    // Ensures SignBlindedMessageTask adds the generated DLEQ proof to the output.
    void attachesDleqProof() throws Exception {
        Mint mint = new Mint(UUID.randomUUID().toString());
        PrivateKey privateKey = PrivateKey.generateRandom();
        when(mintProtocolService.getPrivateKeyForSigning(any(), any(), eq(mint))).thenReturn(privateKey);

        DLEQProof proof = DLEQProof.forBlindSignature("e".repeat(64), "f".repeat(64));
        when(dleqProofGenerator.generateProof(any(), any(), any())).thenReturn(proof);

        SignBlindedMessageTask task = new SignBlindedMessageTask(
                mint,
                blindedMessageOnGenerator(),
                mintProtocolService,
                signatureVaultService,
                dleqProofGenerator
        );

        var result = task.execute();

        assertThat(result.getDleq()).isNotNull();
        assertThat(result.getDleq().getE()).isEqualTo(proof.getE());
        assertThat(result.getDleq().getS()).isEqualTo(proof.getS());
    }

    @Test
    @DisplayName("Fails the request when DLEQ proof generation fails")
    // The mint advertises NUT-12, so it must not return an unproven signature when the proof cannot be made.
    void failsClosedWhenDleqGenerationFails() throws Exception {
        Mint mint = new Mint(UUID.randomUUID().toString());
        PrivateKey privateKey = PrivateKey.generateRandom();
        when(mintProtocolService.getPrivateKeyForSigning(any(), any(), eq(mint))).thenReturn(privateKey);
        when(dleqProofGenerator.generateProof(any(), any(), any()))
                .thenThrow(new IllegalStateException("proof generation broke"));

        SignBlindedMessageTask task = new SignBlindedMessageTask(
                mint,
                blindedMessageOnGenerator(),
                mintProtocolService,
                signatureVaultService,
                dleqProofGenerator
        );

        assertThatThrownBy(task::execute)
                .isInstanceOf(CashuErrorException.class)
                .extracting(t -> ((CashuErrorException) t).getErrorCode().name()).isEqualTo("dleq_generation_failed");
        verifyNoInteractions(signatureVaultService);
    }

    private static BlindedMessage blindedMessageOnGenerator() {
        String blindedHex = Hex.toHexString(CURVE.getG().getEncoded(true));
        return BlindedMessage.builder()
                .amount(1)
                .keySetId(KeysetId.fromString("abcd1234abcd1234"))
                .blindedMessage(PublicKey.fromString(blindedHex))
                .build();
    }
}
