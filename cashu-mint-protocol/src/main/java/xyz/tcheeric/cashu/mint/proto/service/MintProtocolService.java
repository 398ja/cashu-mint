package xyz.tcheeric.cashu.mint.proto.service;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.gateway.Gateway;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;

public interface MintProtocolService {
    Gateway createGateway(@NonNull PaymentMethod method);
    PrivateKey getPrivateKey(@NonNull String keySetId, @NonNull Integer amount, @NonNull Mint mint) throws CashuErrorException;
    MintEntity toMintEntity(@NonNull Mint mint);
    <T extends xyz.tcheeric.cashu.common.Secret> ProofEntity toProofEntity(@NonNull Proof<T> proof, @NonNull MintEntity mintEntity);
}
