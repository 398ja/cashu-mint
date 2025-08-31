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
import xyz.tcheeric.cashu.entities.rest.ErrorResponse;
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
            ErrorResponse error = new ErrorResponse("sign_private_key_not_found");
            throw new CashuErrorException(error.toJson());
        }

        byte[] signature = BDHKEUtils.signBlindedMessage(blindedMessage.getBlindedMessage().toBytes(), privateKey.toBytes());
        // Signature.fromBytes expects 64-byte Schnorr signatures, while blind signatures (C)
        // are 33-byte compressed points. Convert 33-byte results to hex and use fromString.
        if (signature != null && signature.length == 64) {
            return new BlindSignature(blindedMessage.getAmount(), blindedMessage.getKeySetId(), Signature.fromBytes(signature));
        } else {
            String hex = bytesToHex(signature);
            return new BlindSignature(blindedMessage.getAmount(), blindedMessage.getKeySetId(), Signature.fromString(hex));
        }
    }

    private PrivateKey getPrivateKey(@NonNull BlindedMessage blindedMessage, @NonNull Mint mint) throws CashuErrorException {
        return mintProtocolService.getPrivateKey(blindedMessage.getKeySetId().toString(), blindedMessage.getAmount(), mint);
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder hexString = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }

}
