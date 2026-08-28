package xyz.tcheeric.cashu.mint.admin.tests.nap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

import nostr.crypto.bech32.Bech32;
import nostr.crypto.bech32.Bech32Prefix;
import nostr.crypto.schnorr.Schnorr;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import xyz.tcheeric.nap.client.NapProofBuilder;

/**
 * Signs a test client into the admin API through a real NAP handshake and hands
 * back the session cookie to replay on later requests.
 *
 * <p>Shared by the integration and the E2E suites: a session is the only way into
 * the admin API, so both suites must prove they can open one the same way.
 */
public final class NapTestHandshake {

    /**
     * Audience the server expects proofs to name. It is the deployment's
     * {@code nap.external-base-url}, not the address the client dials — the E2E
     * stack answers on a mapped port but signs against this one.
     */
    public static final String EXTERNAL_BASE_URL = "http://localhost:7778";

    /** Private key of the Super Administrator both suites configure. */
    public static final String SUPER_ADMIN_PRIVATE_KEY =
        "0000000000000000000000000000000000000000000000000000000000000001";

    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private NapTestHandshake() {
    }

    /** @return the x-only public key of {@code privateKeyHex}, lower-case hex */
    public static String pubkey(final String privateKeyHex) {
        try {
            return HEX.formatHex(Schnorr.genPubKey(HEX.parseHex(privateKeyHex)));
        } catch (final Exception ex) {
            throw new IllegalStateException("Could not derive a public key", ex);
        }
    }

    /** @return the bech32 npub of {@code privateKeyHex} */
    public static String npub(final String privateKeyHex) {
        return Bech32.toBech32(Bech32Prefix.NPUB, pubkey(privateKeyHex));
    }

    /** @return a fresh private key, so each provisioned Operator signs as itself */
    public static String randomPrivateKey() {
        final byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        key[31] |= 1;
        return HEX.formatHex(key);
    }

    /**
     * Runs the NIP-98 handshake against a running admin API.
     *
     * @param baseUrl        where the admin API answers
     * @param privateKeyHex  the key to sign as
     * @return the {@code name=value} session cookie to send on later requests
     */
    public static String sessionCookie(final String baseUrl, final String privateKeyHex) {
        final RestTemplate restTemplate = new RestTemplate();
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        final ResponseEntity<String> init = restTemplate.postForEntity(
            baseUrl + "/api/v1/auth/init",
            new HttpEntity<>("{\"npub\":\"" + npub(privateKeyHex) + "\"}", headers),
            String.class);

        try {
            final JsonNode challenge = MAPPER.readTree(init.getBody());
            final String body = "{\"challenge_id\":\"" + challenge.get("challenge_id").asText() + "\"}";
            final HttpHeaders completeHeaders = new HttpHeaders();
            completeHeaders.setContentType(MediaType.APPLICATION_JSON);
            completeHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
            completeHeaders.set(HttpHeaders.AUTHORIZATION, new NapProofBuilder()
                .privateKey(privateKeyHex)
                .pubkey(pubkey(privateKeyHex))
                .url(EXTERNAL_BASE_URL + "/api/v1/auth/complete")
                .method("POST")
                .challenge(challenge.get("challenge").asText())
                .challengeId(challenge.get("challenge_id").asText())
                .body(body)
                .buildAuthorizationHeader());

            final ResponseEntity<String> completed = restTemplate.postForEntity(
                baseUrl + "/api/v1/auth/complete",
                new HttpEntity<>(body, completeHeaders),
                String.class);
            final String setCookie = completed.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
            if (setCookie == null) {
                throw new IllegalStateException("The handshake returned no session cookie");
            }
            return setCookie.split(";", 2)[0];
        } catch (final RuntimeException ex) {
            throw ex;
        } catch (final Exception ex) {
            throw new IllegalStateException("The NAP handshake failed against " + baseUrl, ex);
        }
    }
}
