# Deploy the Web Admin Interface to Production

This guide covers building, deploying, and monitoring the mint-admin-web frontend in a production environment.

## Prerequisites

- Node.js 22+
- The mint-admin-rest backend running and reachable
- A reverse proxy (nginx, Caddy, etc.) for single-origin deployment

## Build for Production

```bash
cd mint-admin-web
npm ci
npm run build
```

This produces optimized static assets in `mint-admin-web/dist/`.

## Single-Origin Deployment

The frontend expects API calls to reach the backend at the same origin under `/admin/*` paths. Use a reverse proxy to route:

| Path | Target |
|------|--------|
| `/admin/**` | `http://backend:8080` |
| `/**` | Static files from `dist/` |

### Nginx Example

```nginx
server {
    listen 443 ssl;
    server_name admin.example.com;

    root /var/www/mint-admin-web/dist;
    index index.html;

    location /admin/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

The `try_files` fallback to `index.html` is required for client-side routing.

## Health Checks

- **Backend**: `GET /actuator/health` returns `{"status":"UP"}` when the REST API is healthy.
- **Frontend**: Verify that the built `index.html` is served by requesting the root URL.

Combine both in your load balancer health check:

```bash
curl -sf http://localhost:8080/actuator/health && curl -sf http://localhost/index.html
```

## Rolling Update / Rollback

1. Build the new version: `npm run build`
2. Copy `dist/` to a versioned directory (e.g., `dist-v1.2.0/`)
3. Update the reverse proxy root to point to the new directory
4. Verify the deployment by checking the browser console for errors
5. To rollback, point the reverse proxy root back to the previous version directory

## Monitoring

### Key Metrics

- **Request correlation**: Every API request includes an `X-Correlation-Id` header. Use this to trace requests from browser → proxy → backend → database.
- **Error rates**: Monitor 4xx/5xx responses on the `/admin/*` proxy routes.
- **Asset load times**: The bundle analysis script (`npm run analyze`) reports chunk sizes and gzip sizes for tracking bundle growth.

### Logging

The frontend logs API errors to the browser console with correlation IDs. For server-side logging, configure the backend's logging level:

```properties
logging.level.com.cashu.admin=DEBUG
```
