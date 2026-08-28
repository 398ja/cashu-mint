# Create a mint and put it into service

This guide takes you from an empty environment to a mint that is signing: create
it in the admin interface, activate it, and hand over the one step the interface
cannot do for you — pointing a mint service at the same vault.

The work is split between two people. An **Operator** does everything in the
admin interface. Someone with access to the deployment does step 4, once per
environment rather than once per mint.

## Before you start

- The admin interface open, signed in as an Operator with the
  `mint:lifecycle` permission (see
  [Configure NAP admin authentication](configure-nap-admin-authentication.md)).
- A `cashu-vault-jpa` service and a HashiCorp Vault, both reachable from the
  admin and from the mint service.

## 1. Create the mint

Go to **Mints** and press **Create Mint**.

| Field | What to put in it |
|---|---|
| **Mint ID** | Press **Generate**. This identity is what ties the mint to its key material, and to the mint service that will serve it. |
| **Display Name** | Required. How the mint is listed. |
| Description, Tags | Optional. Tags are comma-separated. |
| **Unit** | `sat`, `usd`, or `eur`. |
| **Denominations** | Comma-separated integers, defaulting to `1,2,4,8,16,32,64,128`. These are the denominations the mint can sign, so choose the range you actually need. |
| Configuration | Free-form JSON, `{}` unless you have a reason. Unit and Denominations are merged into it for you. |

Press **Create Mint**. You land on the mint's detail page.

Unit and denominations are the only two settings that change what gets
provisioned. Everything else is recorded and versioned, but does not affect the
keyset.

## 2. Wait for provisioning

Creating the mint starts a background saga: it derives a keyset and writes one
key per denomination into HashiCorp Vault. Watch the state badge on the detail
page.

| State | Meaning |
|---|---|
| `PROVISIONING` | The saga is writing key material. |
| `PROVISIONED` | Key material exists. The mint is ready to activate. |
| `PROVISION_FAILED` | The vault could not be written to. The admin service log names the cause; fix it and retry from the detail page. |

Once it reads `PROVISIONED`, back up the keys before the mint takes traffic: see
[Back up a keyset's private keys](back-up-keyset-private-keys.md).

## 3. Activate

On the detail page press **Activate**. You will be asked for a reason, which is
recorded in the audit trail.

The page only offers the actions that are legal for the mint's current state, so
what you see changes as the mint moves:

| State | Actions offered |
|---|---|
| `PROVISIONED` | Activate, Retire |
| `ACTIVE` | Pause, Retire |
| `SUSPENDED` | Resume, Retire |
| `DECOMMISSIONED` | none |

**Activation can be refused.** A unit may have only one active mint at a time,
so if another mint is already `ACTIVE` for the same unit, you get an error
banner saying so. Pause or retire the incumbent first.

## 4. Point a mint service at the same vault

This step has no screen, and cannot have one: it is deployment configuration.
Until it is done the mint exists and is active in the admin, and **no mint
service is serving it**.

There is no mint-id setting on the mint service. It discovers which mints it
serves by reading the shared vault, so the vault *is* the link:

```yaml
MINT_PRELOAD_ENABLED: "false"        # serve keysets from the vault, not preload JSON
CASHU_VAULT_BASE_URL: http://cashu-vault-jpa:3333
VAULT_BASE_URL: http://cashu-vault-jpa:3333
VAULT_BACKEND: HASHICORP
VAULT_HASHI_ENABLED: "true"          # without this it silently falls back to the DB vault
VAULT_HASHI_URI: http://hashicorp-vault:8200
VAULT_HASHI_AUTH_METHOD: TOKEN
VAULT_HASHI_AUTH_TOKEN: <token>
VAULT_HASHI_ENGINE_MOUNT: cashu      # must match the admin's mount
```

`MINT_PRELOAD_ENABLED` decides whether the link is real. Left at its default
(`true`), the mint serves a fixed keyset from a JSON file: the admin and the mint
share a vault and still disagree, and a rotation never reaches `/v1/keysets`.

The engine mount must match on both sides, or the mint looks for the keys the
admin wrote under a path that does not exist.

## 5. Confirm the mint is serving

Open the mint's **Keysets** page from the detail page. It lists the keysets the
admin provisioned, with their denominations and where each key lives.

To confirm the *mint service* agrees, ask it what it advertises:

```bash
curl -s "$MINT_URL/v1/keysets" | jq
```

The keyset should be listed and `active`. An empty list means the mint service is
reading a different vault, or is still on preload JSON — revisit step 4.

## Rotating keys later

From the detail page go to **Operations** and press **Rotate Keys**, giving a
reason. The controls table on that page records what happened, reading
"Keyset X replaces [Y]" once the rotation completes.

Afterwards `/v1/keysets` advertises the new keyset as active, and the previous
one as inactive. Retired keysets are never deleted: tokens they signed stay
redeemable, which is why NUT-02 requires them to go on verifying.

## One vault serves every mint in it

A vault-backed mint service serves **every** mint in the vault it reads, not just
one. To keep environments apart, give each its own vault and admin database
rather than pointing several mint services at a shared one.
