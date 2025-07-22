package xyz.tcheeric.cashu.mint.proto.tasks;

import lombok.NonNull;
import xyz.tcheeric.cashu.common.BlindSignature;
import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Mint;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.common.Signature;
import xyz.tcheeric.cashu.common.util.CashuErrorException;
import xyz.tcheeric.cashu.common.util.Task;
import xyz.tcheeric.cashu.crypto.BDHKEUtils;
import xyz.tcheeric.cashu.mint.proto.util.MintProtocolUtil;


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

    private static PrivateKey getPrivateKey(@NonNull BlindedMessage blindedMessage, @NonNull Mint mint) throws CashuErrorException {
        return MintProtocolUtil.getPrivateKey(blindedMessage.getKeySetId(), blindedMessage.getAmount(), mint);
    }

}
