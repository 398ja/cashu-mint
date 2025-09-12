# Configuration

This reference lists configuration properties used by the mint and their default values.

| Property | Default | Description |
| --- | --- | --- |
| `cashu_mint_port` | `7777` | Port for the mint server. |
| `cashu.units` | `sat` | Default unit used for amounts (also used as a fallback when no unit is provided in NUT‑06). |
| `gateway.<method>` |  | FQCN of gateway implementation for a payment method (e.g., `gateway.bolt11=xyz.tcheeric.gateway.phoenixd.PhoenixdGateway`). |
| `gateway.<method>.<unit>` |  | FQCN of gateway for a specific method and unit (e.g., `gateway.bolt11.sat=…`). Takes precedence over `gateway.<method>`. |
| `cashu.expiry` | `15` | Token expiry time in minutes. |

## See also
- [Getting started](../tutorials/getting-started.md)
- [Run tests](../how-to/run-tests.md)
- [Architecture overview](../explanations/architecture-overview.md)
 - [Configure gateways](../how-to/configure-gateways.md)
