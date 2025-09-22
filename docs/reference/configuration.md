# Configuration

This reference lists configuration properties used by the mint and their default values.

| Property | Default | Description |
| --- | --- | --- |
| `cashu_mint_port` | `7777` | Port for the mint server. |
| `cashu.units` | `sat` | Default unit used for amounts (also used as a fallback when no unit is provided in NUT‑06). |
| `gateway.<method>` |  | FQCN of gateway implementation for a payment method (e.g., `gateway.bolt11=xyz.tcheeric.gateway.phoenixd.PhoenixdGateway`). |
| `gateway.<method>.<unit>` |  | FQCN of gateway for a specific method and unit (e.g., `gateway.bolt11.sat=…`). Takes precedence over `gateway.<method>`. |
| `cashu.expiry` | `15` | Token expiry time in minutes. |

### Environment overrides (highest precedence)

At runtime, gateway mappings can be overridden via environment variables. These take precedence over `proto.properties`.

- `GATEWAY_<METHOD>_<UNIT>` (uppercased) – unit-specific, e.g. `GATEWAY_BOLT11_SAT=xyz.tcheeric.gateway.dummy.DummyGateway`
- `GATEWAY_<METHOD>` (uppercased) – per-method fallback, e.g. `GATEWAY_BOLT11=xyz.tcheeric.gateway.phoenixd.PhoenixdGateway`

If a unit-specific override is not set, the method-level override is used; if neither is set, `proto.properties` mappings apply.

Note: The Docker Compose `dev` profile sets `GATEWAY_BOLT11_SAT` (and `GATEWAY_BOLT11`) to the Dummy gateway by default for local development. Override these by exporting different values when starting compose.

## See also
- [Getting started](../tutorials/getting-started.md)
- [Run tests](../how-to/run-tests.md)
- [Architecture overview](../explanations/architecture-overview.md)
 - [Configure gateways](../how-to/configure-gateways.md)
