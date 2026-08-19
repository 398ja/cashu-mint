package xyz.tcheeric.cashu.mint.proto.service.impl;

import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.nut18.PaymentMethod;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.mint.proto.service.MintInfoService;
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;
import xyz.tcheeric.cashu.vault.db.model.MintEntity;
import xyz.tcheeric.cashu.vault.db.model.ProofEntity;
import xyz.tcheeric.payment.adapter.core.common.Gateway;

@Service
public class DefaultMintProtocolService implements MintProtocolService {

    private MintInfoService mintInfoService; // optional, resolved when running under Spring

    public DefaultMintProtocolService() {
    }

    @Autowired(required = false)
    public void setMintInfoService(MintInfoService mintInfoService) {
        this.mintInfoService = mintInfoService;
    }

    @Override
    public Gateway createGateway(@NonNull PaymentMethod method) {
        String unit = resolveUnitForMethod(method);
        return MintProtocolUtil.createGateway(method, unit);
    }

    @Override
    public Gateway createGateway(@NonNull PaymentMethod method, String unit) {
        return MintProtocolUtil.createGateway(method, unit);
    }

    @Override
    public PrivateKey getPrivateKey(@NonNull String keySetId, @NonNull Integer amount, @NonNull Mint mint) throws CashuErrorException {
        return MintProtocolUtil.getPrivateKey(keySetId, amount, mint);
    }

    @Override
    public void requireActiveKeySet(@NonNull String keySetId) throws CashuErrorException {
        MintProtocolUtil.requireActiveKeySet(keySetId);
    }

    @Override
    public MintEntity toMintEntity(@NonNull Mint mint) {
        return MintProtocolUtil.toMintEntity(mint);
    }

    @Override
    public <T extends xyz.tcheeric.cashu.common.Secret> ProofEntity toProofEntity(@NonNull Proof<T> proof, @NonNull MintEntity mintEntity) {
        return MintProtocolUtil.toProofEntity(proof, mintEntity);
    }

    private String resolveUnitForMethod(PaymentMethod method) {
        if (mintInfoService == null) {
            return null; // fall back to gateway.<method>
        }
        try {
            MintInfo info = mintInfoService.getMintInfo();
            if (info != null && info.getNuts() != null) {
                String methodKey = method.name().toLowerCase();
                for (MintInfo.Nut nut : info.getNuts().values()) {
                    if (nut.getMethods() == null) continue;
                    for (MintInfo.Nut.Method m : nut.getMethods()) {
                        if (m.getMethod() != null && m.getMethod().equalsIgnoreCase(methodKey)) {
                            if (m.getUnit() != null && !m.getUnit().isBlank()) return m.getUnit();
                        }
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }
}
