package xyz.tcheeric.cashu.mint.rest.client;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.cashu.common.KeySet;
import xyz.tcheeric.cashu.entities.rest.KeySetResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class CashuClientTest {

    @Test
    public void keyset_callsEndpoint() {
        CashuClient client = Mockito.spy(new CashuClient());
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        Mockito.doReturn("http://localhost:8080/").when(client).getBaseUrl();
        Mockito.doReturn(restTemplate).when(client).getRestTemplate();

        KeySet keySet = Mockito.mock(KeySet.class);
        KeySetResponse response = new KeySetResponse(List.of(keySet));
        when(restTemplate.getForObject(eq("http://localhost:8080/keys/keyset/test"), eq(KeySetResponse.class))).thenReturn(response);

        KeySet actual = client.keyset("test");
        assertSame(keySet, actual);
    }
}
