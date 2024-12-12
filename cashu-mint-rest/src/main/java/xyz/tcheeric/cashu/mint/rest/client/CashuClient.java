package xyz.tcheeric.cashu.mint.rest.client;


import xyz.tcheeric.cashu.common.model.KeySet;
import xyz.tcheeric.cashu.common.model.rest.ActiveKeySetResponse;
import xyz.tcheeric.cashu.common.model.rest.KeySetResponse;

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


    public ActiveKeySetResponse keysets() {
        String url = getBaseUrl() + "/keysets";
        return restTemplate.getForObject(url, ActiveKeySetResponse.class);
    }


    protected String getBaseUrl() {
        String address = System.getProperty("server.address") != null ? System.getProperty("server.address") : serverAddress;
        String port = System.getProperty("server.port") != null ? System.getProperty("server.port") : (serverPort != null ? serverPort : "8080");
        return "http://" + address + ":" + port + "/";
    }

}
