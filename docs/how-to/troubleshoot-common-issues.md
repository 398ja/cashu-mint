# Troubleshoot Common Issues

This guide consolidates solutions for frequently encountered problems when building, running, and testing cashu-mint.

## Build Issues

### Java Version Mismatch

**Symptom:** Build fails with "Java version mismatch" or class file version errors.

**Solution:** Ensure Java 21 is active:
```bash
java -version   # Must show 21+
mvn --version   # Check Maven uses the right JDK
```

The Maven enforcer plugin rejects builds on older Java versions. Set `JAVA_HOME` explicitly if needed:
```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
```

### Google Java Format Failures

**Symptom:** CI fails with formatting errors.

**Solution:** Reformat before committing:
```bash
mvn com.spotify.fmt:fmt-maven-plugin:format
```

### Dependency Resolution Failures

**Symptom:** Build cannot resolve `cashu-lib`, `payment-adapter`, or `cashu-vault` artifacts.

**Solution:** These are published to a private Maven registry. Ensure your `~/.m2/settings.xml` includes the correct repository credentials. For local development, build the dependency projects first with `mvn install`.

## Docker Compose Issues

### Port Conflicts

**Symptom:** `docker compose up` fails with "port is already allocated."

**Solution:** Check for conflicting services:
```bash
# Check key ports
ss -tlnp | grep -E '7777|3333|8080|55433|55434'
```

Stop the conflicting service or change ports in the compose file.

### Services Fail Health Checks

**Symptom:** `docker compose ps` shows services as unhealthy.

**Solution:**
1. Check logs: `docker compose logs <service-name>`
2. Verify the vault database is reachable before starting the mint.
3. Check the mint seeded the vault at startup: its log should carry
   `Seeded the vault with mint ... keyset ...`, or say the keyset was already
   present.

### Container Cannot Reach Host Services

**Symptom:** Mint container cannot connect to a locally running Phoenixd or vault.

**Solution:** Use `host.docker.internal` (Docker Desktop) or `--add-host=host.docker.internal:host-gateway` (Linux) to reach host services from inside containers.

## Gateway Issues

### Gateway Class Not Found

**Symptom:** Startup fails with `ClassNotFoundException` for the configured gateway.

**Solution:** Verify the gateway class is on the classpath and the environment variable is spelled correctly:
```bash
echo $GATEWAY_BOLT11_SAT
# Should be a fully qualified class name, e.g.:
# xyz.tcheeric.payment.adapter.ln.phoenixd.PhoenixdGateway
```

### Lightning Invoice Not Paid

**Symptom:** `POST /v1/mint/bolt11` returns 402 with `mint_invoice_not_paid_error`.

**Solution:**
- Verify the invoice was paid on the Lightning backend.
- Check gateway connectivity: the mint must reach the Phoenixd/LND REST API.
- Review gateway logs for timeout or authentication errors.

### Webhook Notifications Not Arriving

**Symptom:** Mint quotes stay in UNPAID state despite payment.

**Solution:**
1. Verify `WEBHOOK_SECRET` is set identically on both the mint and the payment gateway.
2. Check that the gateway can reach the mint's webhook endpoint (`POST /webhook/payment`).
3. The mint falls back to polling when webhooks are unavailable; check logs for polling activity.

## Vault Issues

### Vault Connection Refused

**Symptom:** Mint logs show connection refused errors to the vault URL.

**Solution:**
```bash
# Verify the vault is running and reachable
curl http://localhost:3333/actuator/health

# Check the configured URL
echo $CASHU_VAULT_BASE_URL
```

### Keysets Endpoint Returns 500

**Symptom:** `GET /v1/keysets` returns 500.

**Solution:** The vault holds no keyset for the mint. The mint seeds one at startup
from `scripts/preload-test-data.json`; check its log for
`Seeded the vault with mint ...` or a `Failed to seed the vault` warning naming the
cause. An empty `{"keysets":[]}` rather than a 500 usually means the mint is
reaching a different vault than the one the admin provisioned into.

To regenerate the preload data:
```bash
./mvnw -q -pl cashu-mint-tools -Ppreload-json exec:java
```

## Test Issues

### Integration Tests Fail Locally

**Symptom:** Integration tests fail with connection errors.

**Solution:** Ensure Docker services are running before running integration tests:
```bash
docker compose -f docker-compose.dev.yml up -d
mvn clean verify -Pintegration-tests
```

### Tests Run Slowly

**Symptom:** Regular builds include integration tests.

**Solution:** Integration tests are excluded by default. Verify the active profile:
```bash
mvn help:active-profiles
```

If integration tests are running unexpectedly, ensure the `skip-integration-tests` profile is not being overridden.

### MockBean Deprecation Warnings

**Symptom:** Warnings about `@MockBean` deprecation in test output.

**Solution:** This is a Spring Boot 3.5+ deprecation warning. Tests still pass correctly. No action required.

## WebSocket Issues

### Cannot Connect to /v1/ws

**Symptom:** WebSocket connections are refused or immediately closed.

**Solution:**
1. Verify WebSocket is enabled: `cashu.websocket.enabled=true` (default).
2. If behind a reverse proxy, ensure it supports WebSocket upgrade headers.
3. Check CORS: `cashu.websocket.allowed-origins` must include your client's origin.

### No Notifications Received

**Symptom:** Subscribed but no state change notifications arrive.

**Solution:**
1. Verify the subscription was acknowledged (check for the `subId` in the response).
2. Ensure you subscribed to the correct kind and filter IDs.
3. Trigger a state change (e.g., pay an invoice) and verify the mint processes it.

## See Also

- [Development workflow](development-workflow.md)
- [Run tests](run-tests.md)
- [Error codes](../reference/error-codes.md)
- [Virtual thread issues](../runbooks/virtual-thread-issues.md)
