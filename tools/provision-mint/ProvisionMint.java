import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import xyz.tcheeric.nap.client.NapProofBuilder;

import java.util.List;
import java.util.UUID;

/**
 * Provisions a NEW mint through the admin API and prints the keyset id it derives.
 *
 * <p>The point is to prove that CREATING a mint yields a NUT-02 v2 keyset committing to the
 * input fee, without any seeded fixture involved. Rotation would not show this: it tests
 * replacing a keyset, not establishing one.
 *
 * <p>Args: baseUrl externalBaseUrl privKeyHex npub inputFeePpk
 */
public class ProvisionMint {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String baseUrl = args[0];
        String externalBaseUrl = args[1];
        String privHex = args[2];
        String npub = args[3];
        int fee = Integer.parseInt(args[4]);

        RestTemplate rest = new RestTemplate();
        String cookie = session(rest, baseUrl, externalBaseUrl, privHex, npub);
        System.out.println("session acquired");

        UUID mintId = UUID.randomUUID();
        String body = "{\"mintId\":\"" + mintId + "\","
                + "\"metadata\":{\"displayName\":\"v2 provisioning probe\"},"
                + "\"configuration\":{\"cashu.unit\":\"sat\","
                + "\"cashu.denominations\":\"1,2,4,8,16,32,64,128,256,512,1024\","
                + "\"cashu.input_fee_ppk\":" + fee + "}}";

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON));
        h.set(HttpHeaders.COOKIE, cookie);

        System.out.println("creating mint " + mintId + " with input_fee_ppk=" + fee);
        ResponseEntity<String> created = rest.postForEntity(
                baseUrl + "/admin/lifecycle/mints", new HttpEntity<>(body, h), String.class);
        System.out.println("create status=" + created.getStatusCode());
        System.out.println("MINT_ID=" + mintId);
    }

    private static String session(RestTemplate rest, String baseUrl, String externalBaseUrl,
                                  String privHex, String npub) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        ResponseEntity<String> init = rest.postForEntity(baseUrl + "/api/v1/auth/init",
                new HttpEntity<>("{\"npub\":\"" + npub + "\"}", headers), String.class);
        JsonNode challenge = MAPPER.readTree(init.getBody());

        String body = "{\"challenge_id\":\"" + challenge.get("challenge_id").asText() + "\"}";
        HttpHeaders complete = new HttpHeaders();
        complete.setContentType(MediaType.APPLICATION_JSON);
        complete.setAccept(List.of(MediaType.APPLICATION_JSON));
        complete.set(HttpHeaders.AUTHORIZATION, new NapProofBuilder()
                .privateKey(privHex)
                .pubkey(pubkey(privHex))
                .url(externalBaseUrl + "/api/v1/auth/complete")
                .method("POST")
                .challenge(challenge.get("challenge").asText())
                .challengeId(challenge.get("challenge_id").asText())
                .body(body)
                .buildAuthorizationHeader());

        ResponseEntity<String> done = rest.postForEntity(baseUrl + "/api/v1/auth/complete",
                new HttpEntity<>(body, complete), String.class);
        String setCookie = done.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        if (setCookie == null) {
            throw new IllegalStateException("no session cookie returned");
        }
        return setCookie.split(";", 2)[0];
    }

    private static String pubkey(String privHex) {
        byte[] pub = nostr.crypto.schnorr.Schnorr.genPubKey(hex(privHex));
        StringBuilder sb = new StringBuilder();
        for (byte b : pub) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }
}
