package xyz.tcheeric.cashu.mint.rest.voucher;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIdempotencyKey;
import xyz.tcheeric.cashu.mint.proto.ports.VoucherIdempotencyKeyRepository;
import xyz.tcheeric.cashu.mint.rest.config.VoucherDurabilityProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Spec 003 FR-009 / T312 — durable Idempotency-Key handling on voucher
 * POST endpoints. Replays the cached response when the same
 * {@code (Idempotency-Key, principal_id)} + matching
 * {@code request_hash} arrives again; returns 409
 * {@code idempotency_key_conflict} when the key matches but the
 * request_hash does not (tamper signal).
 *
 * <p>The filter is a no-op when the durable repository is not wired
 * (legacy unit-test contexts).
 */
@Slf4j
@Component
public class VoucherIdempotencyKeyFilter extends OncePerRequestFilter {

    private static final String VOUCHER_PATH_PREFIX = "/v1/vouchers";
    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final VoucherDurabilityProperties properties;

    @Autowired(required = false)
    private VoucherIdempotencyKeyRepository repository;

    public VoucherIdempotencyKeyFilter(VoucherDurabilityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith(VOUCHER_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (repository == null) {
            chain.doFilter(request, response);
            return;
        }

        String idempotencyKey = request.getHeader(HEADER_IDEMPOTENCY_KEY);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"idempotency_key_required\"}");
            return;
        }

        String principalId = resolvePrincipal();
        if (principalId == null) {
            chain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper bufferedRequest = new ContentCachingRequestWrapper(request);
        // Force the body to be buffered before we can read it.
        bufferedRequest.getInputStream().readAllBytes();
        String requestHash = sha256Hex(bufferedRequest.getContentAsByteArray());

        Optional<VoucherIdempotencyKey> existing = repository.findByKey(idempotencyKey, principalId);
        if (existing.isPresent()) {
            VoucherIdempotencyKey row = existing.get();
            if (!row.requestHash().equals(requestHash)) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"idempotency_key_conflict\"}");
                log.warn("voucher_idempotency_conflict principal={} key={}", principalId, idempotencyKey);
                return;
            }
            response.setStatus(row.responseStatus());
            response.setContentType("application/json");
            response.getWriter().write(row.responseBodyJson());
            log.info("voucher_idempotency_replay principal={} key={} status={}",
                    principalId, idempotencyKey, row.responseStatus());
            return;
        }

        ContentCachingResponseWrapper bufferedResponse = new ContentCachingResponseWrapper(response);
        chain.doFilter(bufferedRequest, bufferedResponse);

        // Only cache successful responses; client errors should be retryable
        // without locking in the same payload contract.
        int status = bufferedResponse.getStatus();
        if (status >= 200 && status < 300) {
            String body = new String(bufferedResponse.getContentAsByteArray(), StandardCharsets.UTF_8);
            repository.save(new IdempotencyRow(
                    idempotencyKey,
                    principalId,
                    requestHash,
                    status,
                    body,
                    Instant.now().plus(properties.getIdempotencyKeyTtl()),
                    Instant.now()));
        }

        bufferedResponse.copyBodyToResponse();
    }

    private static String resolvePrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : null;
    }

    private static String sha256Hex(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Inline carrier for the JPA adapter's {@code save} call. */
    private record IdempotencyRow(
            String idempotencyKey,
            String principalId,
            String requestHash,
            int responseStatus,
            String responseBodyJson,
            Instant expiresAt,
            Instant createdAt) implements VoucherIdempotencyKey {
    }
}
