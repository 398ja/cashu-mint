package xyz.tcheeric.cashu.mint.rest.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import xyz.tcheeric.cashu.mint.proto.util.MintInfo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

public class CashuClientTest {

    private CashuClient client;
    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        System.setProperty("server.address", "localhost");
        System.setProperty("cashu_mint_port", "8080");
        client = new CashuClient();
        mockServer = MockRestServiceServer.bindTo(client.getRestTemplate()).build();
    }

    @AfterEach
    void tearDown() {
        mockServer.verify();
        System.clearProperty("server.address");
        System.clearProperty("cashu_mint_port");
    }

    // Ensures keys() targets the versioned keys endpoint.
    @Test
    void keysShouldCallVersionedKeysEndpoint() {
        mockServer.expect(requestTo("http://localhost:8080/v1/keys"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"keysets\":[]}", MediaType.APPLICATION_JSON));

        assertNotNull(client.keys());
    }

    // Ensures info() targets the versioned info endpoint and parses the response.
    @Test
    void infoShouldCallVersionedInfoEndpoint() {
        String response = """
                {
                  \"name\": \"Test Mint\",
                  \"pubkey\": \"pubkey\",
                  \"version\": \"1.0.0\"
                }
                """;
        mockServer.expect(requestTo("http://localhost:8080/v1/info"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        MintInfo info = client.info();
        assertEquals("Test Mint", info.getName());
        assertEquals("pubkey", info.getPubkey());
    }
}
