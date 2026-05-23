package xyz.tcheeric.cashu.mint.rest.spec003.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Spec 003 — test-only controller mounted under {@code /v1/vouchers}
 * so the auth + rate-limit + idempotency filters exercise the same
 * route prefix the production {@code VoucherController} owns, without
 * pulling in the real Nostr-coupled {@code VoucherService} stack.
 *
 * <p>The filters are path-prefix matchers; they fire for any request
 * under {@code /v1/vouchers/**} regardless of which controller serves it.
 * This lets the US3 ITs drive a deterministic 200 response and assert
 * filter behaviour in isolation.
 */
@TestConfiguration
public class VoucherTestEchoConfig {

    @RestController
    @RequestMapping("/v1/vouchers")
    public static class VoucherTestEchoController {

        /**
         * Echo endpoint — returns 200 with the payload echoed back.
         * The voucher filters run first (auth, rate-limit, idempotency);
         * if any rejects, control never reaches this method.
         */
        @PostMapping("/_test_echo")
        public ResponseEntity<Map<String, Object>> echo(@RequestBody(required = false) Map<String, Object> body) {
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "echoed", body == null ? Map.of() : body));
        }
    }
}
