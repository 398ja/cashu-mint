package xyz.tcheeric.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import xyz.tcheeric.cashu.common.PrivateKey;
import xyz.tcheeric.cashu.mint.admin.MintUtil;
import xyz.tcheeric.cashu.mint.admin.model.KeySetDto;
import xyz.tcheeric.cashu.mint.admin.model.MintDto;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.util.Objects;

public class MintUtilTest extends MintUtil {

    public MintUtilTest(@NonNull String mintId, @NonNull String unit) throws Exception {
        this(mintId, unit, Objects.requireNonNull(MintUtil.class.getResource("/keyset.properties")));
    }

    public MintUtilTest(@NonNull String mintId, @NonNull String unit, @NonNull URL keysetProperties) throws Exception {
        super(mintId, unit, keysetProperties);
    }

    @Override
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