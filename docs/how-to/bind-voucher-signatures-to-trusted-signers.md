# Bind voucher signatures to trusted signers

This guide shows an operator how to turn on the mint's check that a voucher was signed by a key
the mint recognises for the issuer the voucher names, without breaking voucher swaps while the
keys are being collected (cashu-mint#527).

A voucher carries its own `issuer_pubkey`, and the mint verifies the issuer signature against
that key. Without this check, a voucher signed with any keypair, naming any `issuerId`, verifies
and can be swapped for ordinary proofs. The check only looks at vouchers that carry a signature,
and only after that signature has verified.

## Before you start

- You need to set environment variables on the mint (`imani-mint-rest` in the Imani deploy).
- You need read access to the mint's logs, and to its Prometheus metrics if you run the
  observability stack.
- Know who signs your vouchers. In the Imani deployment the issuer and the signer differ: the
  merchant is the `issuerId`, while **gateway-customer** signs every voucher with its own Nostr
  identity key (the one kept in its `~/.cashu` volume). So the gateway key goes in
  `trusted-signers`, not under a merchant's `issuer-keys` entry.

## 1. Deploy in `log` mode

`log` is the default, so a deploy that sets nothing is already in it. Every signed voucher is
still accepted. Each one whose signer is not trusted for its issuer is logged and counted:

```text
WARN  voucher_issuer_untrusted mode=log voucherId=… issuerHash=3f9a0c1d2e4b signerPrefix=79be667ef9dc
```

- `signerPrefix` is the first 12 hex characters of the signing key.
- `issuerHash` is a SHA-256 prefix of the `issuerId`. Raw merchant keys are never logged.
- The counter is `cashu_mint_voucher_issuer_untrusted_total{mode="log"}`.

At boot, the mint logs `voucher_issuer_binding mode=log with no … issuer-keys …` while nothing is
configured. That warning is expected at this step.

## 2. Collect the signing keys for a week

Group the `voucher_issuer_untrusted` lines by `signerPrefix`. For each prefix, find the full key
it belongs to:

- **gateway-customer's identity key.** Read it from the gateway's identity store. Its prefix
  should account for nearly every line.
- **A merchant that signs its own vouchers.** Register its key under that merchant's `issuerId`.
- **A prefix you cannot account for.** Investigate before going further. It is either a signer you
  did not know about or a self-issued voucher, which is exactly what `enforce` will refuse.

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

Redeploy, still in `log`, and confirm the counter stops growing:

```promql
increase(cashu_mint_voucher_issuer_untrusted_total{mode="log"}[24h]) == 0
```

## 4. Switch to `enforce`

```bash
CASHU_MINT_VOUCHER_ISSUER_BINDING=enforce
```

Now a signed voucher from an untrusted signer is refused with `voucher_signature_invalid`, and
counted under `mode="enforce"`.

The mint refuses to start in `enforce` when neither `issuer-keys` nor `trusted-signers` has a
key. Otherwise it would refuse every signed voucher.

## Roll back

Set `CASHU_MINT_VOUCHER_ISSUER_BINDING=log` and redeploy. `off` skips the check entirely and
exists for emergencies only.

## What this does not cover

- **Unsigned vouchers.** They are still accepted, as before. Whether the mint should require a
  signature to be present is a separate decision.
- **Rotating the gateway's identity key.** Add the new key to `trusted-signers` before the gateway
  starts signing with it. Keep the old one listed while vouchers it signed are still circulating.

See the [configuration reference](../reference/configuration.md#voucher-signer-trust) for the
properties.
