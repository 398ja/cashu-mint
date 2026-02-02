package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.NonNull;
import org.bouncycastle.math.ec.ECPoint;
import xyz.tcheeric.cashu.common.nut12.DLEQProof;
import xyz.tcheeric.cashu.crypto.DLEQUtils;
import xyz.tcheeric.cashu.mint.proto.service.DLEQProofGenerator;

import java.math.BigInteger;

/**
 * Default implementation for generating NUT-12 DLEQ proofs using {@link DLEQUtils}.
 */
public class DefaultDLEQProofGenerator implements DLEQProofGenerator {

    @Override
    public DLEQProof generateProof(
            @NonNull BigInteger privateKey,
            @NonNull ECPoint blindedMessage,
            @NonNull ECPoint blindSignature
    ) {
        var proof = DLEQUtils.generateProof(privateKey, blindedMessage, blindSignature);
        return DLEQProof.forBlindSignature(proof.e(), proof.s());
    }
}
