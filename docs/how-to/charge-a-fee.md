# Charge a fee for spending tokens

This guide shows an Operator how to make a mint charge the NUT-02 `input_fee_ppk`
fee on the tokens it issues, and how to change that fee later.

Fees are off by default. A mint that has never been configured with one charges
nothing, exactly as it did before fees existed.

## Before you start

You need Operator access to the admin API and the id of the mint you are
provisioning. Read [A fee change is a keyset
rotation](../adr/0009-a-fee-change-is-a-keyset-rotation.md) first if you are
changing a fee rather than setting one, because the fee is fixed for the life of
a keyset.

## Understand what the number means

`input_fee_ppk` is charged per thousand *inputs spent*, not as a percentage of
the amount. The mint computes:

```
fee = (sum of input_fee_ppk for each input's keyset + 999) / 1000
```

So the fee depends on how many proofs a wallet spends, not on their value:

| `input_fee_ppk` | Cost of spending 2 proofs | Cost of spending 10 proofs |
| --- | --- | --- |
| `0` | 0 sat | 0 sat |
| `100` | 1 sat (rounded up from 0.2) | 1 sat |
| `1000` | 2 sat | 10 sat |

The division rounds up, so any non-zero fee costs at least one unit. A fee of
`100` therefore charges 1 sat whether the wallet spends two proofs or ten.

## Set the fee when provisioning a mint

Include `cashu.input_fee_ppk` in the configuration when you create the mint:

```bash
curl -X POST https://admin.example/admin/lifecycle/mints \
  -H 'Content-Type: application/json' \
  -d '{
        "mintId": "00000001-0000-0000-0000-000000000001",
        "metadata": { "name": "Example mint" },
        "configuration": {
          "cashu.unit": "sat",
          "cashu.input_fee_ppk": 100
        }
      }'
```

The value is stored on the keyset in the shared vault, which is how it reaches
the mint. Omitting it, or setting it to `0`, provisions a mint that charges
nothing.

## Confirm the mint is charging it

Two places should agree. The admin reports what was provisioned:

```bash
curl https://admin.example/admin/lifecycle/mints/{mintId}/keysets
```

```json
{ "items": [ { "keySetId": "009a1f293253e41e", "state": "SIGNING", "inputFeePpk": 100 } ] }
```

The mint publishes what wallets will pay:

```bash
curl https://mint.example/v1/keysets
```

```json
{ "keysets": [ { "id": "009a1f293253e41e", "unit": "sat", "active": true, "input_fee_ppk": 100 } ] }
```

If the admin shows a fee and the mint does not, the mint is serving a different
keyset; check that it reads from the same vault the admin provisions into.

## Change the fee later

A fee change is a keyset rotation, not an edit. Update the configured fee and
then rotate the mint's keys:

```bash
curl -X PUT https://admin.example/admin/lifecycle/mints/{mintId} \
  -H 'Content-Type: application/json' \
  -d '{ "configuration": { "cashu.input_fee_ppk": 200 } }'

curl -X POST https://admin.example/admin/operations/mints/{mintId}/keys/rotate \
  -H 'Content-Type: application/json' \
  -d '{ "reason": "Fee change to 200 ppk" }'
```

The rotation provisions a new keyset at the new fee and archives the old one.
Tokens already issued keep the old fee, because the archived keyset goes on
redeeming them indefinitely. The rotation is recorded in the Audit Trail against
you, naming the fee it applied.

Updating the configuration without rotating changes nothing: the mint keeps
signing with the keyset it already has, at the fee that keyset was created with.

## Turn fees off

Set the fee back to `0` and rotate. As with any other fee change, the keyset
charging the old fee stays available for redemption.

## Related

- [A fee change is a keyset rotation](../adr/0009-a-fee-change-is-a-keyset-rotation.md) - why changing a fee rotates the keyset.
- [Configure the mint](configure-mint.md) - the mint's own configuration properties.
