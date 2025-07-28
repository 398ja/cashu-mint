package xyz.tcheeric.cashu.mint.rest.client;


import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.entities.rest.ActiveKeySetResponse;
import xyz.tcheeric.cashu.entities.rest.KeySetResponse;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

import java.util.List;
import java.util.Objects;

public class CashuClient extends BaseClient {

    public CashuClient() {
        super();
    }

    public List<KeySet> keys() {
        String url = getBaseUrl() + "/keys";
        KeySetResponse response = restTemplate.getForObject(url, KeySetResponse.class);
        return Objects.requireNonNull(response).getKeysets();
    }

    public List<KeySet> keys(String keysetId) {
        String url = getBaseUrl() + "/keys/" + keysetId;
        KeySetResponse response = restTemplate.getForObject(url, KeySetResponse.class);
        return Objects.requireNonNull(response).getKeysets();
    }

    /**
     * Retrieve a specific key set by id using the {@code /keys/keyset/{keyset_id}} endpoint.
     *
     * @param keysetId the id of the key set
     * @return the {@link KeySet} instance
     */
    public KeySet keyset(String keysetId) {
        String url = getBaseUrl() + "/keys/keyset/" + keysetId;
        KeySetResponse response = restTemplate.getForObject(url, KeySetResponse.class);
        List<KeySet> keysets = Objects.requireNonNull(response).getKeysets();
        return keysets.isEmpty() ? null : keysets.getFirst();
    }


    public ActiveKeySetResponse keysets() {
        String url = getBaseUrl() + "/keysets";
        return restTemplate.getForObject(url, ActiveKeySetResponse.class);
    }

    public MintInfo info() {
        String url = getBaseUrl() + "/info";
        return restTemplate.getForObject(url, MintInfo.class);
    }


    // TODO: Use the Configuration class to get the server address and port. Get rid of the super class
    protected String getBaseUrl() {
        String address = System.getProperty("server.address") != null ? System.getProperty("server.address") : serverAddress;
        String port = System.getProperty("server.port") != null ? System.getProperty("server.port") : (serverPort != null ? serverPort : "8080");
        return "http://" + address + ":" + port + "/";
    }

}
