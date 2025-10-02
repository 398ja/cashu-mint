package xyz.tcheeric.cashu.mint.proto.service;

import lombok.NonNull;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;
import xyz.tcheeric.gateway.common.Gateway;

@Service
public class DefaultMintProtocolService implements MintProtocolService {
    @Override
    public Gateway createGateway(@NonNull PaymentMethod method) {
        return MintProtocolUtil.createGateway(method);
    }

    @Override
    public PrivateKey getPrivateKey(@NonNull String keySetId, @NonNull Integer amount, @NonNull Mint mint) throws CashuErrorException {
        return MintProtocolUtil.getPrivateKey(keySetId, amount, mint);
    }

    @Override
    public MintEntity toMintEntity(@NonNull Mint mint) {
        return MintProtocolUtil.toMintEntity(mint);
    }

    @Override
    public <T extends xyz.tcheeric.cashu.common.Secret> ProofEntity toProofEntity(@NonNull Proof<T> proof, @NonNull MintEntity mintEntity) {
        return MintProtocolUtil.toProofEntity(proof, mintEntity);
    }
}
