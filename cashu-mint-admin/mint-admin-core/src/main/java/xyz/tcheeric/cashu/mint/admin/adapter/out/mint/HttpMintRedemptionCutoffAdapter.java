package xyz.tcheeric.cashu.mint.admin.adapter.out.mint;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import xyz.tcheeric.cashu.mint.admin.application.port.out.MintRedemptionCutoffPort;

/**
 * Delivers a redemption cutoff to the mint's {@code PUT /admin/keysets/{id}/redemption-cutoff}
 * over HTTP Basic (cashu-mint#551).
 *
 * <p>A 4xx is the mint's verdict on the cutoff and is final, so it surfaces as {@link Rejected}.
 * A 5xx or a transport failure may pass, so it throws and the outbox retries. With no mint URL
 * configured every delivery is rejected with that reason, so an approved request ends visibly
 * {@code FAILED} instead of retrying forever against nothing.
 */
public class HttpMintRedemptionCutoffAdapter implements MintRedemptionCutoffPort {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String authorization;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpMintRedemptionCutoffAdapter(final String baseUrl, final String username, final String password,
                                           final ObjectMapper objectMapper) {
        this(baseUrl, username, password, objectMapper,
            HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    HttpMintRedemptionCutoffAdapter(final String baseUrl, final String username, final String password,
                                    final ObjectMapper objectMapper, final HttpClient httpClient) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.replaceAll("/+$", "");
        this.authorization = password == null || password.isBlank() ? null
            : "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.objectMapper = Objects.requireNonNull(objectMapper, "object mapper must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "http client must not be null");
    }

    @Override
    public void apply(final String keysetId, final Instant redeemUntil, final String reason, final String setBy) {
        if (baseUrl == null || authorization == null) {
            throw new Rejected("mint-admin has no mint admin channel configured "
                + "(ADMIN_MINT_BASE_URL and ADMIN_MINT_PASSWORD); the cutoff was not delivered");
        }
        final HttpResponse<String> response = send(keysetId, body(redeemUntil, reason, setBy));
        final int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        if (status >= 400 && status < 500 && status != 408 && status != 429) {
            throw new Rejected("mint answered " + status + ": " + response.body());
        }
        throw new IllegalStateException("mint answered " + status + " to a redemption cutoff; will retry");
    }

    private HttpResponse<String> send(final String keysetId, final String body) {
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/admin/keysets/"
                + URLEncoder.encode(keysetId, StandardCharsets.UTF_8) + "/redemption-cutoff"))
            .timeout(TIMEOUT)
            .header("Authorization", authorization)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (final IOException e) {
            throw new IllegalStateException("mint unreachable for redemption cutoff: " + e.getMessage(), e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted delivering redemption cutoff", e);
        }
    }

    private String body(final Instant redeemUntil, final String reason, final String setBy) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                "redeemUntil", redeemUntil.toString(),
                "reason", reason == null ? "" : reason,
                "setBy", setBy == null ? "" : setBy));
        } catch (final JsonProcessingException e) {
            throw new IllegalStateException("could not encode redemption cutoff", e);
        }
    }
}
