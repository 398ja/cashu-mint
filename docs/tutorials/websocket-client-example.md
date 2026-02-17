# WebSocket Client Tutorial

This tutorial walks through connecting to the cashu-mint WebSocket endpoint and subscribing to real-time state changes. By the end, you will observe proof and quote state transitions during a mint flow.

## Prerequisites

- A running cashu-mint instance (see [Getting started](getting-started.md))
- `wscat` installed (`npm install -g wscat`) or a browser with JavaScript console
- `curl` for issuing mint requests

## Connect to the WebSocket Endpoint

The mint exposes a JSON-RPC 2.0 WebSocket endpoint at `/v1/ws`.

**Using wscat:**

```bash
wscat -c ws://localhost:7777/v1/ws
```

You should see `Connected` with no immediate output. The server waits for subscription requests.

## Subscribe to Proof State Changes

Send a JSON-RPC 2.0 `subscribe` request for the `proof_state` kind. The `filters` array specifies which Y-values (hash-to-curve of proof secrets) to monitor.

```json
{
  "jsonrpc": "2.0",
  "id": "sub-proof-1",
  "method": "subscribe",
  "params": {
    "kind": "proof_state",
    "filters": [{"ids": ["02abc123...", "03def456..."]}]
  }
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": "sub-proof-1",
  "result": {
    "status": "OK",
    "subId": "proof_state-abc123"
  }
}
```

The `subId` identifies this subscription for later unsubscription.

## Subscribe to Mint Quote State Changes

Subscribe to `bolt11_mint_quote` to observe mint quote lifecycle transitions (UNPAID -> PAID -> ISSUED):

```json
{
  "jsonrpc": "2.0",
  "id": "sub-quote-1",
  "method": "subscribe",
  "params": {
    "kind": "bolt11_mint_quote",
    "filters": [{"ids": ["quote-id-from-api"]}]
  }
}
```

Replace `"quote-id-from-api"` with an actual quote ID from `POST /v1/mint/quote/bolt11`.

## Subscribe to Melt Quote State Changes

Subscribe to `bolt11_melt_quote` to observe melt quote transitions (UNPAID -> PENDING -> PAID):

```json
{
  "jsonrpc": "2.0",
  "id": "sub-melt-1",
  "method": "subscribe",
  "params": {
    "kind": "bolt11_melt_quote",
    "filters": [{"ids": ["melt-quote-id"]}]
  }
}
```

## Observe a Mint Flow

Open two terminals. In terminal 1, connect via wscat and subscribe to mint quotes.

In terminal 2, create a mint quote:

```bash
curl -s -X POST http://localhost:7777/v1/mint/quote/bolt11 \
  -H "Content-Type: application/json" \
  -d '{"amount": 1000}' | jq
```

Copy the `quote` ID from the response and use it in your subscription filter (terminal 1).

When the Lightning invoice is paid, terminal 1 receives a notification:

```json
{
  "jsonrpc": "2.0",
  "method": "subscribe",
  "params": {
    "subId": "bolt11_mint_quote-abc123",
    "payload": {
      "quote": "quote-id",
      "request": "lnbc...",
      "state": "PAID",
      "paid": true,
      "expiry": 1700000000
    }
  }
}
```

After minting tokens with `POST /v1/mint/bolt11`, the state changes to `ISSUED`.

## Unsubscribe

To stop receiving notifications for a subscription:

```json
{
  "jsonrpc": "2.0",
  "id": "unsub-1",
  "method": "unsubscribe",
  "params": {
    "subId": "bolt11_mint_quote-abc123"
  }
}
```

**Response:**

```json
{
  "jsonrpc": "2.0",
  "id": "unsub-1",
  "result": {
    "status": "OK",
    "subId": "bolt11_mint_quote-abc123"
  }
}
```

## JavaScript Example

Connect from a browser or Node.js:

```javascript
const ws = new WebSocket("ws://localhost:7777/v1/ws");

ws.onopen = () => {
  // Subscribe to mint quote updates
  ws.send(JSON.stringify({
    jsonrpc: "2.0",
    id: "sub-1",
    method: "subscribe",
    params: {
      kind: "bolt11_mint_quote",
      filters: [{ ids: ["your-quote-id"] }]
    }
  }));
};

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);

  if (msg.id) {
    // Subscription confirmation
    console.log("Subscribed:", msg.result.subId);
  } else if (msg.method === "subscribe") {
    // State change notification
    console.log("State change:", msg.params.payload);
  }
};

ws.onerror = (err) => console.error("WebSocket error:", err);
ws.onclose = () => console.log("Disconnected");
```

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `cashu.websocket.enabled` | `true` | Enable/disable the WebSocket endpoint |
| `cashu.websocket.allowed-origins` | `*` | CORS allowed origins (restrict in production) |

## See Also

- [Supported NUTs](../reference/nuts.md) (NUT-17 for WebSocket spec)
- [REST API reference](../reference/rest-api.md)
- [Getting started](getting-started.md)
