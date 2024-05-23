package cashu.mint.rest.client;

import lombok.Getter;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.ParameterizedType;

public class QuoteClient<T> {

    protected enum Operation {
        MINT,
        MELT
    }

    @Getter
    private final Operation operation;

    @Value("${server.address}")
    private String serverAddress;

    @Value("${server.port}")
    private String serverPort;

    @Getter
    private RestTemplate restTemplate;

    public QuoteClient(@NonNull Operation operation) {
        this.operation = operation;
        this.restTemplate = new RestTemplate();
    }

    public T createQuote(@NonNull T entity) {
        HttpEntity<T> request = new HttpEntity<>(entity);
        ResponseEntity<T> response = restTemplate.exchange(getBaseUrl(), HttpMethod.POST, request, getGenericClass(0));
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
        String port = System.getProperty("server.port") != null ? System.getProperty("server.port") : serverPort;
        return "http://" + address + ":" + port + "/" + operation.name().toLowerCase() + "/quote";
    }
}