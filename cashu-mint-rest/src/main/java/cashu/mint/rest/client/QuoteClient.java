package cashu.mint.rest.client;

import cashu.util.Configuration;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.java.Log;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;

@Getter
@Log
public class QuoteClient<T> extends BaseClient {

    public enum Operation {
        MINT,
        MELT
    }

    private final QuoteClient.Operation operation;

    public QuoteClient(@NonNull Operation operation) {
        super();
        this.operation = operation;
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

    @Override
    protected String getBaseUrl() {
        String address = System.getProperty("server.address") != null ? System.getProperty("server.address") : serverAddress;
        String port = System.getProperty("server.port") != null ? System.getProperty("server.port") : (serverPort != null ? serverPort : "8080");
        return "http://" + address + ":" + port + "/" + operation.name().toLowerCase() + "/quote";
    }

    @SuppressWarnings("unchecked")
    private Class<T> getGenericClass(int index) {
        return (Class<T>) ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments()[index];
    }

    private void setConfigAttributes() {
        try (InputStream inputStream = QuoteClient.class.getResourceAsStream("/application.properties")) {
            if (inputStream == null) {
                throw new FileNotFoundException("Could not find application.properties");
            }
            Configuration configuration = Configuration.load(inputStream);
            serverAddress = configuration.getValue("server.address");
            serverPort = configuration.getValue("server.port");
        } catch (IOException e) {
            throw new RuntimeException("Error reading application.properties", e);
        }
    }
}