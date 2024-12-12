package xyz.tcheeric.cashu.mint.rest.client;

import lombok.Getter;
import org.springframework.web.client.RestTemplate;

public abstract class BaseClient {

    protected String serverAddress;

    protected String serverPort;

    @Getter
    protected final RestTemplate restTemplate;

    public BaseClient() {
        this.restTemplate = new RestTemplate();
    }

    protected abstract String getBaseUrl();

}
