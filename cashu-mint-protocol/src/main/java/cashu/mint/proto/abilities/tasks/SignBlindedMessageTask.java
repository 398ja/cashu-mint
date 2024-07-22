package cashu.mint.proto.abilities.tasks;

import cashu.common.model.BlindSignature;
import cashu.common.model.BlindedMessage;
import cashu.common.model.Mint;
import cashu.common.model.PrivateKey;
import cashu.common.model.Signature;
import cashu.common.protocol.BaseAbility;
import cashu.common.protocol.CashuErrorException;
import cashu.crypto.BDHKEUtils;
import cashu.mint.proto.util.MintUtil;
import lombok.NonNull;

public class SignBlindedMessageTask implements BaseAbility.Task<BlindSignature> {

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
        return MintUtil.getPrivateKey(blindedMessage.getKeySetId(), blindedMessage.getAmount(), mint);
    }

}
