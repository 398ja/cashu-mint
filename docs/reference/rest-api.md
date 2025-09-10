# REST API Reference
This document provides details about the Cashu Mint REST API endpoints. All paths are rooted at `/v1`.

## Endpoints

## Endpoint details


### `GET /keys/{mint_id}/generate`
Generate keyset IDs for a mint.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `mint_id` | path | string | Unique mint identifier. |
**Sample request**
```http
GET /keys/123/generate HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "keyset_ids": ["abc123"]
}
```


### `GET /keys/keyset/{keyset_id}`
Retrieve keys for a specific keyset.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `keyset_id` | path | string | Identifier of the keyset. |
**Sample request**
```http
GET /keys/keyset/abc123 HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "keys": ["-----BEGIN PUBLIC KEY-----\n..."]
}
```


### `GET /keysets`
List active keysets.
_No parameters._
**Sample request**
```http
GET /keysets HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "keysets": ["abc123", "def456"]
}
```


### `POST /swap/{mint_id}`
Swap tokens within a mint.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `mint_id` | path | string | Unique mint identifier. |
| `body` | body | object | Swap request payload. |
**Sample request**
```http
POST /swap/123 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "inputs": [],
  "outputs": []
}
```
**Sample response**
```json
{
  "token": "..."
}
```


### `POST /mint/quote/{method}`
Request a mint quote for a payment method.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `method` | path | string | Payment method (e.g., `bolt11`). |
| `body` | body | object | Quote request payload. |
**Sample request**
```http
POST /mint/quote/bolt11 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "amount": 100
}
```
**Sample response**
```json
{
  "quote_id": "q123",
  "request": "lnbc1..."
}
```


### `GET /mint/quote/{method}/{quote_id}`
Check status of a mint quote.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `method` | path | string | Payment method. |
| `quote_id` | path | string | Identifier returned from quote request. |
**Sample request**
```http
GET /mint/quote/bolt11/q123 HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "paid": true
}
```


### `POST /mint/{mintId}/{method}`
Mint tokens using a payment method.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `mintId` | path | string | Unique mint identifier. |
| `method` | path | string | Payment method. |
| `body` | body | object | Mint request payload. |
**Sample request**
```http
POST /mint/123/bolt11 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "quote_id": "q123"
}
```
**Sample response**
```json
{
  "token": "..."
}
```


### `POST /melt/quote/{method}`
Request a melt quote for a payment method.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `method` | path | string | Payment method (e.g., `bolt11`). |
| `body` | body | object | Quote request payload. |
**Sample request**
```http
POST /melt/quote/bolt11 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "amount": 100
}
```
**Sample response**
```json
{
  "quote_id": "m123",
  "request": "lnbc1..."
}
```


### `GET /melt/quote/{method}/{quote_id}`
Check status of a melt quote.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `method` | path | string | Payment method. |
| `quote_id` | path | string | Identifier returned from melt quote request. |
**Sample request**
```http
GET /melt/quote/bolt11/m123 HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "paid": true
}
```


### `POST /melt/{mint_id}/{method}`
Melt tokens using a payment method.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `mint_id` | path | string | Unique mint identifier. |
| `method` | path | string | Payment method. |
| `body` | body | object | Melt request payload. |
**Sample request**
```http
POST /melt/123/bolt11 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "quote_id": "m123",
  "token": "..."
}
```
**Sample response**
```json
{
  "paid": true
}
```


### `GET /info`
Retrieve mint information.
_No parameters._
**Sample request**
```http
GET /info HTTP/1.1
Host: example.com
```
**Sample response**
```json
{
  "name": "Cashu Mint"
}
```


### `POST /checkstate/{mint_id}`
Check state of tokens against a mint.
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `mint_id` | path | string | Unique mint identifier. |
| `body` | body | object | State check payload. |
**Sample request**
```http
POST /checkstate/123 HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "tokens": []
}
```
**Sample response**
```json
{
  "spent": []
}
```


### `POST /restore`
Restore blind signatures for previously signed messages (NUT-09).
| Parameter | In | Type | Description |
| --- | --- | --- | --- |
| `body` | body | object | Restore request payload containing blinded messages. |
**Sample request**
```http
POST /restore HTTP/1.1
Host: example.com
Content-Type: application/json
{
  "blinded_messages": [
    { "B_": "..." }
  ]
}
```
**Sample response**
```json
{
  "outputs": [ { "B_": "..." } ],
  "signatures": [ "..." ]
}
```
