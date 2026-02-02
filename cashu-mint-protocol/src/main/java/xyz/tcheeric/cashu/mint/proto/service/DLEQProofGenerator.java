package xyz.tcheeric.cashu.mint.proto.service;

import org.bouncycastle.math.ec.ECPoint;
import xyz.tcheeric.cashu.common.nut12.DLEQProof;

import java.math.BigInteger;

/**
 * Service for generating NUT-12 DLEQ proofs for blind signatures.
 */
public interface DLEQProofGenerator {

    /**
     * Generates a DLEQ proof for a blind signature to prove the same private key
     * was used for the mint public key and the blind signature.
     *
     * @param privateKey     the mint private key (scalar a)
     * @param blindedMessage the blinded message point B'
     * @param blindSignature the blind signature point C' = a * B'
     * @return DLEQ proof (e, s) ready to attach to a blind signature
     */
    DLEQProof generateProof(BigInteger privateKey, ECPoint blindedMessage, ECPoint blindSignature);
}
