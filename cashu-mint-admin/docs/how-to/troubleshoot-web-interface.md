# Troubleshoot the Web Admin Interface

Common issues and resolution steps for the mint-admin-web frontend.

## Session Expired

**Symptom**: Redirected to `/login?expired=true` with "Session expired" message.

**Cause**: The backend returned HTTP 401, so there is no session — it expired, or it was revoked.

**Resolution**:
1. Sign in again with your Nostr key.
2. If the issue persists, check `GET /api/v1/auth/session` directly to see what the backend makes of the cookie.
3. Check that the reverse proxy is forwarding the `cashu_admin_session` cookie.

## Access Denied

**Symptom**: "Access Denied" message when navigating to a page.

**Cause**: The authenticated user's roles do not include the required role for that page.

**Resolution**:
1. Check your current roles in the sidebar footer.
2. Required roles per page:
   - Mints: `MINT_ADMIN`
   - Users: `USER_ADMIN`
   - Alerts: `ALERTS_ADMIN`
   - Operations: `OPS_ADMIN`
   - Dashboard and Audit Log: any authenticated role
3. Contact an administrator to assign the missing role.

## Slow Dashboard

**Symptom**: Dashboard takes several seconds to load or shows skeleton loaders for a long time.

**Cause**: The summary and audit endpoints may be slow due to large datasets or database performance.

**Resolution**:
1. Check backend response times using the `X-Correlation-Id` from the browser's network tab.
2. Verify database connection pool is not exhausted: `GET /actuator/health` shows datasource status.
3. Consider adding database indexes if audit event queries are slow.

## API Errors

**Symptom**: Red error banner with an error code (e.g., `ERR_500`, `unknown_error`).

**Resolution**:
1. Note the error code and message displayed in the banner.
2. Open the browser developer console for the full error with correlation ID.
3. Search backend logs for the correlation ID:
   ```bash
   grep "CORRELATION_ID" /var/log/mint-admin/application.log
   ```
4. Click "Retry" on the error banner to re-attempt the request.

## Blank Page After Deploy

**Symptom**: White screen after deploying a new version.

**Cause**: Browser cached old JavaScript chunks that no longer exist.

**Resolution**:
1. Hard refresh the browser (Ctrl+Shift+R / Cmd+Shift+R).
2. Ensure the reverse proxy serves correct `Cache-Control` headers:
   - `dist/assets/*`: `Cache-Control: public, max-age=31536000, immutable` (hashed filenames)
   - `dist/index.html`: `Cache-Control: no-cache` (always re-validate)

## Performance Baselines

Use the bundle analysis script to track frontend asset sizes:

```bash
cd mint-admin-web
npm run analyze
```

Expected baseline ranges for the initial build:

| Metric | Target |
|--------|--------|
| Total JS (gzip) | < 150 KB |
| Total CSS (gzip) | < 15 KB |
| Largest chunk (gzip) | < 100 KB |

## Correlation ID Tracing

Every API request from the frontend includes a unique `X-Correlation-Id` header. To trace a request end-to-end:

1. Open browser DevTools → Network tab
2. Find the failing request and copy its `X-Correlation-Id` request header
3. Search backend logs: `grep "correlation-id-value" application.log`
4. The backend propagates this ID through service calls and database operations
