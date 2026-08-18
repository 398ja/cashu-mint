# Deploy to Production

This checklist covers the essential steps for deploying cashu-mint to a production environment. It assumes familiarity with running Java services and Docker.

## Prerequisites

- Java 21 runtime
- PostgreSQL 15+ (for the vault database)
- A Lightning backend (Phoenixd or compatible gateway)
- A reverse proxy (nginx, Caddy, or similar) for TLS termination
- Docker and Docker Compose (if using containerized deployment)

## 1. Configure TLS

Always run the mint behind a reverse proxy with TLS. The mint itself listens on HTTP; terminate TLS at the proxy.

**Caddy example:**
```
cashu.example.com {
    reverse_proxy localhost:7777
}
```

**nginx example:**
```nginx
server {
    listen 443 ssl;
    server_name cashu.example.com;

    ssl_certificate /etc/letsencrypt/live/cashu.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/cashu.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:7777;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /v1/ws {
        proxy_pass http://127.0.0.1:7777;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

## 2. Secrets Management

Set secrets via environment variables. Never commit them to source control.

| Variable | Purpose |
|----------|---------|
| `PHOENIXD_BASE_URL` | Lightning backend URL |
| `PHOENIXD_API_KEY` | Lightning backend API key |
| `CASHU_VAULT_BASE_URL` | Vault service URL |
| `WEBHOOK_SECRET` | Shared secret for payment webhook HMAC |
| `ADMIN_TOKEN` | Admin API authentication token (if running admin) |

Store these in a secrets manager (Vault, AWS Secrets Manager, systemd credentials) or a `.env` file with restricted permissions (`chmod 600`).

## 3. Docker Compose Deployment

Create a production compose file based on `docker-compose.dev.yml`:

```yaml
services:
  cashu-mint-rest:
    image: docker.398ja.xyz/cashu-mint-rest:latest
    ports:
      - "127.0.0.1:7777:7777"
    environment:
      - CASHU_VAULT_BASE_URL=http://cashu-vault:3333
      - GATEWAY_BOLT11_SAT=xyz.tcheeric.gateway.phoenixd.PhoenixdGateway
      - PHOENIXD_BASE_URL=${PHOENIXD_BASE_URL}
      - PHOENIXD_API_KEY=${PHOENIXD_API_KEY}
      - WEBHOOK_SECRET=${WEBHOOK_SECRET}
      - CASHU_WEBSOCKET_ALLOWED_ORIGINS=https://cashu.example.com
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:9000/actuator/health/readiness"]
      interval: 30s
      timeout: 5s
      retries: 3

  cashu-vault:
    image: docker.398ja.xyz/cashu-vault:latest
    environment:
      - SPRING_DATASOURCE_URL=jdbc:postgresql://vault-db:5432/cashu_vault
      - SPRING_DATASOURCE_USERNAME=${DB_USER}
      - SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD}
    restart: unless-stopped

  vault-db:
    image: postgres:15
    volumes:
      - vault-data:/var/lib/postgresql/data
    environment:
      - POSTGRES_DB=cashu_vault
      - POSTGRES_USER=${DB_USER}
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    restart: unless-stopped

volumes:
  vault-data:
```

Bind the mint port to `127.0.0.1` so only the reverse proxy can reach it.

## 4. Systemd Deployment (Alternative)

If running the JAR directly:

```ini
[Unit]
Description=Cashu Mint REST API
After=network.target

[Service]
Type=simple
User=cashu
ExecStart=/usr/bin/java -Xmx2g -XX:+UseG1GC -jar /opt/cashu-mint/cashu-mint-rest.jar
EnvironmentFile=/opt/cashu-mint/.env
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

## 5. Health Checks

Configure your monitoring system to poll:

- **Readiness:** `GET /actuator/health/readiness` — returns 200 when the mint can accept requests.
- **Liveness:** `GET /actuator/health/liveness` — returns 200 when the JVM is running.
- **Prometheus metrics:** `GET /actuator/prometheus` — scrape for dashboards and alerting.

## 6. Database Backups

Back up the vault PostgreSQL database regularly:

```bash
# Daily backup
pg_dump -U postgres -d cashu_vault | gzip > /backups/cashu_vault_$(date +%Y%m%d).sql.gz

# Retention: keep 30 days
find /backups -name "cashu_vault_*.sql.gz" -mtime +30 -delete
```

Test restoring from backups periodically.

## 7. Log Rotation

If writing logs to files, configure rotation:

```
# /etc/logrotate.d/cashu-mint
/var/log/cashu-mint/*.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
}
```

When using Docker, configure the logging driver:

```yaml
logging:
  driver: json-file
  options:
    max-size: "50m"
    max-file: "5"
```

## 8. Monitoring and Alerting

Enable the observability stack for production monitoring. See [Enable observability](enable-observability.md) for setup instructions.

Key alerts to configure:

| Condition | Metric | Threshold |
|-----------|--------|-----------|
| High error rate | `cashu_mint_requests_total{status=~"5.."}` | > 1% of total |
| Latency spike | `cashu_mint_requests_duration_seconds` p99 | > 2s |
| Connection pool exhaustion | `tomcat_connections_current` | > 80% of max |
| Gateway unreachable | `cashu_mint_health_gateway` | DOWN for > 1m |

## 9. Security Checklist

- [ ] TLS enabled on all public endpoints
- [ ] WebSocket allowed-origins restricted to your domain
- [ ] Mint port bound to localhost (proxy access only)
- [ ] Secrets stored outside of source control
- [ ] Database credentials rotated periodically
- [ ] Rate limiting configured at the proxy level
- [ ] Webhook secret set and HMAC validation enabled
- [ ] Firewall rules restrict vault and database access to internal network

## See Also

- [Configure the mint](configure-mint.md)
- [Enable observability](enable-observability.md)
- [Environment variables](../reference/environment-variables.md)
- [Troubleshoot common issues](troubleshoot-common-issues.md)
