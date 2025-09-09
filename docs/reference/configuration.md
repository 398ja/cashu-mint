# Configuration properties

This reference lists the configuration properties for the mint service. Each property can be overridden via a Java system property or corresponding environment variable.

| Property | Default | Description |
| --- | --- | --- |
| `cashu_mint_port` | `7777` | Port for the mint server; override with system property or `CASHU_MINT_PORT` env var. |
| `cashu.units` | `sat` | Unit used for amounts. |
| `cashu.expiry` | `15` | Token expiry time in minutes. |
| `gateway.prod` | `xyz.tcheeric.gateway.phoenixd.PhoenixdGateway` | Production gateway implementation class. |
| `gateway.mock` | `xyz.tcheeric.gateway.mock.PhoenixdMockGateway` | Mock gateway used for development and tests. |
| `cashu.melt.fee-reserve-percent` | `0.05` | Fraction added as fee reserve during melts. |

