package xyz.tcheeric.cashu.mint.proto.service;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
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
    MintEntity toMintEntity(@NonNull Mint mint);
    <T extends xyz.tcheeric.cashu.common.Secret> ProofEntity toProofEntity(@NonNull Proof<T> proof, @NonNull MintEntity mintEntity);
}
