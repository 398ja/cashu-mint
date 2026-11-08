package xyz.tcheeric.cashu.mint.proto.spending;

import xyz.tcheeric.cashu.common.BlindedMessage;
import xyz.tcheeric.cashu.common.Proof;
import xyz.tcheeric.cashu.common.RandomStringSecret;
import xyz.tcheeric.cashu.common.Secret;
import xyz.tcheeric.cashu.entities.rest.nut03.PostSwapRequest;
import xyz.tcheeric.cashu.voucher.domain.VoucherDocuments;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Swap requests carrying a {@code vouchers} map, for the cashu-voucher#64 tests. */
public final class ByReferenceRequests {

    private ByReferenceRequests() {
    }

    /** A swap with {@code inputs} placeholder inputs carrying {@code documents} under their hashes. */
    public static PostSwapRequest<Secret> swapCarrying(int inputs, byte[]... documents) {
        List<Proof<Secret>> proofs = new ArrayList<>();
        for (int i = 0; i < inputs; i++) {
            Proof<Secret> proof = new Proof<>();
            proof.setAmount(1);
            proof.setSecret(RandomStringSecret.fromString("placeholder-" + i));
            proofs.add(proof);
        }
        PostSwapRequest<Secret> request = new PostSwapRequest<>(proofs, new ArrayList<BlindedMessage>());
        request.setVouchers(hexMap(documents));
        return request;
    }

    /** {@code hex(SHA256(d)) -> hex(d)} for each document. */
    public static Map<String, String> hexMap(byte[]... documents) {
        Map<String, String> map = new TreeMap<>();
        for (byte[] document : documents) {
            map.put(VoucherDocuments.key(document), HexFormat.of().formatHex(document));
        }
        return map;
    }
}
