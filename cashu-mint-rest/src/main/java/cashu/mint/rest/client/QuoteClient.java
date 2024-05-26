package cashu.mint.rest.client;

import cashu.util.Configuration;
import cashu.vault.FSVault;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.java.Log;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.util.Objects;

@Log
public class QuoteClient<T> {

    protected enum Operation {
        MINT,
        MELT
    }

    @Getter
    private final Operation operation;

    private String serverAddress;

    private String serverPort;

    @Getter
    private final RestTemplate restTemplate;

    public QuoteClient(@NonNull Operation operation) {
        this.operation = operation;
        this.restTemplate = new RestTemplate();
        setConfigAttributes();
    }

    public T createQuote(@NonNull T entity) {
        HttpEntity<T> request = new HttpEntity<>(entity);
        String baseUrl = getBaseUrl();
        var genericClass = getGenericClass(0);
        ResponseEntity<T> response = restTemplate.exchange(baseUrl, HttpMethod.POST, request, genericClass);
        return response.getBody();
    }

    public T getByQuoteId(@NonNull String quoteId) {
        String url = getBaseUrl() + "/" + quoteId;
        ResponseEntity<T> response = restTemplate.getForEntity(url, getGenericClass(0));
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Class<T> getGenericClass(int index) {
        return (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[index];
    }

    public String getBaseUrl() {
        String address = System.getProperty("server.address") != null ? System.getProperty("server.address") : serverAddress;
        String port = System.getProperty("server.port") != null ? System.getProperty("server.port") : (serverPort != null ? serverPort : "8080");
        return "http://" + address + ":" + port + "/" + operation.name().toLowerCase() + "/quote";
    }

    private void setConfigAttributes() {
        InputStream inputStream = FSVault.class.getResourceAsStream("/application.properties");
        Configuration configuration = Configuration.load(Objects.requireNonNull(inputStream));
        serverAddress = configuration.getValue("server.address");
        serverPort = configuration.getValue("server.port");
    }
}