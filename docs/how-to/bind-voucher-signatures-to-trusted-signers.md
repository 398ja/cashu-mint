# Bind voucher signatures to trusted signers

This guide shows an operator how to turn on the mint's check that a voucher was signed by a key
the mint recognises for the issuer the voucher names, without breaking voucher swaps while the
keys are being collected (cashu-mint#527).

A voucher carries its own `issuer_pubkey`, and the mint verifies the issuer signature against
that key. Without this check, a voucher signed with any keypair, naming any `issuerId`, verifies
and can be swapped for ordinary proofs. The same mode also governs vouchers that carry no
signature at all, because otherwise stripping `issuer_sig` and `issuer_pubkey` would walk past
the check.

## Before you start

- You need to set environment variables on the mint (`imani-mint-rest` in the Imani deploy).
- You need read access to the mint's logs, and to its Prometheus metrics if you run the
  observability stack.
- Know who signs your vouchers. In the Imani deployment the issuer and the signer differ: the
  merchant is the `issuerId`, while **gateway-customer** signs every voucher with its own Nostr
  identity key (the one kept in its `~/.cashu` volume). So the gateway key goes in
  `trusted-signers`, not under a merchant's `issuer-keys` entry.
- The mint's own voucher key, `voucher.mint.issuerPublicKey`, is trusted automatically. With
  `voucher.enabled=true`, `POST /v1/vouchers` signs with that key, so the mint never refuses its
  own vouchers.

## 1. Deploy in `log` mode

`log` is the default, so a deploy that sets nothing is already in it. Every voucher is still
accepted. Two kinds are logged and counted, each under its own name:

```text
WARN  voucher_issuer_untrusted mode=log voucherId=… issuerHash=3f9a0c1d2e4b signerPrefix=79be667ef9dc
WARN  voucher_unsigned mode=log voucherId=… issuerHash=3f9a0c1d2e4b
```

- `voucher_issuer_untrusted` is a signed voucher whose key is not trusted for its issuer. It is
  counted in `cashu_mint_voucher_issuer_untrusted_total{mode="log"}`.
- `voucher_unsigned` is a voucher with no issuer signature at all. It is counted in
  `cashu_mint_voucher_unsigned_total{mode="log"}`.
- `signerPrefix` is the first 12 hex characters of the signing key.
- `issuerHash` is a SHA-256 prefix of the `issuerId`. Raw merchant keys are never logged.

At boot, the mint logs `voucher_issuer_binding mode=log with no … issuer-keys …` while nothing is
configured. That warning is expected at this step.

## 2. Watch both signals for a week

Group the `voucher_issuer_untrusted` lines by `signerPrefix`. For each prefix, find the full key
it belongs to:

- **gateway-customer's identity key.** Read it from the gateway's identity store. Its prefix
  should account for nearly every line.
- **A merchant that signs its own vouchers.** Register its key under that merchant's `issuerId`.
- **A prefix you cannot account for.** Investigate before going further. It is either a signer you
  did not know about or a self-issued voucher, which is exactly what `enforce` will refuse.

Then look at `voucher_unsigned`. Every legitimate producer in the Imani deployment signs its
vouchers, so this should stay at zero. If it does not, find the producer (the `issuerHash` groups
them) and make it sign before you go further. `enforce` refuses every unsigned voucher.

## 3. Configure the keys

Keys may be x-only (64 hex characters) or compressed (66, with a `02`/`03` prefix), in either
case. A malformed key stops the mint at boot.

```properties
# Keys that sign on behalf of any issuer, comma-separated.
cashu.mint.voucher.trusted-signers=${CASHU_MINT_VOUCHER_TRUSTED_SIGNERS:}

# A key one issuer signs with. Also the key merchant verification trusts for that issuer.
cashu.mint.voucher.issuer-keys.<issuerId>=<hex pubkey>
```

As environment variables:

```bash
CASHU_MINT_VOUCHER_TRUSTED_SIGNERS=<gateway-customer identity pubkey>
```

Redeploy, still in `log`, and confirm that neither counter grows:

```promql
increase(cashu_mint_voucher_issuer_untrusted_total{mode="log"}[24h]) == 0
and increase(cashu_mint_voucher_unsigned_total{mode="log"}[24h]) == 0
```

## 4. Switch to `enforce`

```bash
CASHU_MINT_VOUCHER_ISSUER_BINDING=enforce
```

Now a voucher that is unsigned, or signed by an untrusted key, is refused with
`voucher_signature_invalid` (90019) and counted under `mode="enforce"`.

The mint refuses to start in `enforce` when no key is configured at all: no `issuer-keys`, no
`trusted-signers` and no `voucher.mint.issuerPublicKey`. Otherwise it would refuse every voucher.

A malformed key also stops the boot. The error names the property and position, for example
`cashu.mint.voucher.trusted-signers[1]`, and never repeats the value, in case it is a private key
pasted into the wrong place.

## Roll back

Set `CASHU_MINT_VOUCHER_ISSUER_BINDING=log` and redeploy. `off` skips the check entirely and
exists for emergencies only.

## What this does not cover

- **Rotating the gateway's identity key.** Add the new key to `trusted-signers` before the gateway
  starts signing with it. Keep the old one listed while vouchers it signed are still circulating.

See the [configuration reference](../reference/configuration.md#voucher-signer-trust) for the
properties.
