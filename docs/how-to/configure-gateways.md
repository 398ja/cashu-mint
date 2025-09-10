# Configure gateways

This guide shows how to map payment methods and units to gateway implementations without changing the REST API.

- The mint selects a gateway based on the payment method from the request and the unit configured in NUT‑06 (`mint.yaml`).
- You configure gateway classes in `app.properties` using either a method-wide key or a method+unit key.

## Steps

1. Ensure NUT‑06 (`mint.yaml`) advertises methods and units you support. For example:
   ```yaml
   mint:
     nuts:
       4:
         methods:
           - method: bolt11
             unit: sat
   ```
2. Map gateways in `app.properties`:
   - Prefer unit-specific mapping and keep a method fallback.
   ```properties
   # Unit-specific mapping takes precedence
   gateway.bolt11.sat=xyz.tcheeric.gateway.phoenixd.PhoenixdGateway
   # Fallback if no unit-specific mapping is found
   gateway.bolt11=xyz.tcheeric.gateway.phoenixd.PhoenixdGateway
   ```
3. Restart the mint so changes take effect.

## How selection works

- The service resolves `unit` for a given `method` from NUT‑06 at runtime.
- It loads the gateway class using `gateway.<method>.<unit>`; if absent, it falls back to `gateway.<method>`.
- There are no query parameters added to the REST API; this remains compliant with NUTs.

## Tips

- Keep `mint.yaml` and `app.properties` aligned: only advertise methods/units for which you have a gateway mapping.
- Use different gateways per unit if needed (e.g., `usd` via a card processor, `sat` via LN).

## See also
- [Configuration](../reference/configuration.md)
- [REST API](../reference/rest-api.md)
