package xyz.tcheeric.cashu.mint.proto.service;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

public interface MintProtocolService {
    Gateway createGateway(@NonNull PaymentMethod method);
    Gateway createGateway(@NonNull PaymentMethod method, String unit);
    PrivateKey getPrivateKey(@NonNull String keySetId, @NonNull Integer amount, @NonNull Mint mint) throws CashuErrorException;

    /**
     * Resolve the signing key for a new output, refusing an archived keyset.
     *
     * <p>Deliberately separate from {@link #getPrivateKey}, which the redemption
     * paths also use: archiving retires a keyset for issuance only, and proofs it
     * already signed must keep verifying and melting. See ADR-0004.
     *
     * @param keySetId external keyset id the client asked to be signed against
     * @param amount denomination to sign
     * @param mint the mint
     * @return the signing key
     * @throws CashuErrorException {@code keyset_inactive} when the keyset is archived
     */
    PrivateKey getPrivateKeyForSigning(@NonNull String keySetId, @NonNull Integer amount, @NonNull Mint mint)
            throws CashuErrorException;
    MintEntity toMintEntity(@NonNull Mint mint);
    <T extends xyz.tcheeric.cashu.common.Secret> ProofEntity toProofEntity(@NonNull Proof<T> proof, @NonNull MintEntity mintEntity);
}
