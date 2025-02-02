package xyz.tcheeric.cashu.mint.proto.tasks;

import xyz.tcheeric.cashu.common.model.BlindSignature;
import xyz.tcheeric.cashu.common.model.BlindedMessage;
import xyz.tcheeric.cashu.common.model.Mint;
import xyz.tcheeric.cashu.common.model.PrivateKey;
import xyz.tcheeric.cashu.common.model.Signature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;
import lombok.NonNull;

public class SignBlindedMessageTask implements Task<BlindSignature> {

    private final Mint mint;
    private final BlindedMessage blindedMessage;

    public SignBlindedMessageTask(@NonNull Mint mint, @NonNull BlindedMessage blindedMessage) {
        this.mint = mint;
        this.blindedMessage = blindedMessage;
    }

    @Override
    public BlindSignature execute() {
        try {
            PrivateKey privateKey = getPrivateKey(blindedMessage, mint);
            if (privateKey == null) {
                throw new CashuErrorException("Private key not found");
            }

            byte[] signature = BDHKEUtils.signBlindedMessage(blindedMessage.getBlindedMessage().toBytes(), privateKey.toBytes());
            return new BlindSignature(blindedMessage.getAmount(), blindedMessage.getKeySetId(), Signature.fromBytes(signature));
        } catch (CashuErrorException e) {
            throw new RuntimeException(e);
        }
    }

    private static PrivateKey getPrivateKey(@NonNull BlindedMessage blindedMessage, @NonNull Mint mint) {
        return MintProtocolUtil.getPrivateKey(blindedMessage.getKeySetId(), blindedMessage.getAmount(), mint);
    }

}
