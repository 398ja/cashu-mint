package xyz.tcheeric.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.mint.admin.MintUtil;
import xyz.tcheeric.cashu.mint.admin.model.KeySetDto;
import xyz.tcheeric.cashu.mint.admin.model.MintDto;

import java.io.IOException;
import java.math.BigInteger;
import java.util.UUID;

public class MintUtilTest extends MintUtil {

    public MintUtilTest() {
        super(UUID.randomUUID().toString(), "sat");
    }


    protected PrivateKey getPrivateKey(@NonNull Integer key) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            MintDto localMint = mapper.readValue(
                    getClass().getResourceAsStream("/mint.json"),
                    MintDto.class
            );
            for (KeySetDto keySet : localMint.getKeySets()) {
                PrivateKey hexKey = keySet.getKeys().getValues().get(BigInteger.valueOf(key));
                if (hexKey != null) {
                    return hexKey;
                }
            }
            throw new IllegalArgumentException("No private key found for key: " + key);
        } catch (IOException e) {
            throw new RuntimeException("Unable to read mint.json", e);
        }
    }
}