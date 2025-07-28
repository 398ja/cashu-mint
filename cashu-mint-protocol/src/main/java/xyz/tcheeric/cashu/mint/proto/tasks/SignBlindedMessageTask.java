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
import xyz.tcheeric.cashu.mint.proto.service.MintProtocolService;


public class SignBlindedMessageTask implements Task<BlindSignature> {

    private final Mint mint;
    private final BlindedMessage blindedMessage;
    private final MintProtocolService mintProtocolService;
    public SignBlindedMessageTask(@NonNull Mint mint, @NonNull BlindedMessage blindedMessage, @NonNull MintProtocolService mintProtocolService) {
        this.mint = mint;
        this.blindedMessage = blindedMessage;
        this.mintProtocolService = mintProtocolService;
    }

    @Override
    public BlindSignature execute() throws CashuErrorException {
        PrivateKey privateKey = getPrivateKey(blindedMessage, mint);
        if (privateKey == null) {
            throw new CashuErrorException("Private key not found");
        }

        byte[] signature = BDHKEUtils.signBlindedMessage(blindedMessage.getBlindedMessage().toBytes(), privateKey.toBytes());
        return new BlindSignature(blindedMessage.getAmount(), blindedMessage.getKeySetId(), Signature.fromBytes(signature));
    }

    private PrivateKey getPrivateKey(@NonNull BlindedMessage blindedMessage, @NonNull Mint mint) throws CashuErrorException {
        return mintProtocolService.getPrivateKey(blindedMessage.getKeySetId(), blindedMessage.getAmount(), mint);
    }

}
