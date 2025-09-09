# Configuration Properties

This reference lists configuration properties for the mint server.

| Property | Default | Description |
| --- | --- | --- |
| `cashu_mint_port` | `7777` | Port for the mint server; override with system property or `CASHU_MINT_PORT` env var. |
| `cashu.units` | `sat` | Unit used for amounts. |
| `cashu.expiry` | `15` | Token expiry time in minutes. |
| `gateway.prod` | `xyz.tcheeric.gateway.phoenixd.PhoenixdGateway` | Production gateway implementation class. |
| `gateway.mock` | `xyz.tcheeric.gateway.mock.PhoenixdMockGateway` | Mock gateway used for development and tests. |
| `cashu.melt.fee-reserve-percent` | `0.05` | Fraction added as fee reserve during melts. |

The gateway used by the protocol is chosen via the `PROFILE` (or `ENV`) environment variable. If `PROFILE` is set to `prod`, the class defined in `gateway.prod` is instantiated; otherwise the `gateway.mock` implementation is used. When running with Docker Compose, start the matching Compose profile (for example `docker compose --profile prod up`) and set `PROFILE=prod` to use the real Phoenixd gateway.
