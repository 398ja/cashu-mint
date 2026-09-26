# Stranded ISSUING mint quotes

This runbook covers finding and resolving regular mint quotes stuck in `ISSUING`. Such a quote has taken the payer's money and delivered nothing.

## How a quote strands

A mint request moves the quote `PAID → ISSUING`, signs the outputs, writes the `issuance_record` ledger row, then moves it `ISSUING → ISSUED`. If anything fails after signing, the quote stays in `ISSUING`:

- the wallet gets a 500 and never receives the signatures;
- its retry finds the quote no longer `PAID` and gets `20005 issuance_in_progress`;
- nothing moves it on, because no background job owns `ISSUING`.

The known cause is cashu-mint#494. `issuance_record.keyset_id` was `VARCHAR(64)`, while a NUT-02 v2 keyset id is 66 characters. Once the mint rotated onto a v2 keyset, every regular mint failed at the ledger insert. Migration `V20260926_002` widens the column, so a mint on 0.40.0 or later no longer strands quotes this way.

## Find them

```sql
SELECT q.quote_id, q.amount, q.unit, q.updated_at
FROM mint_quote q
WHERE q.lifecycle_state = 'ISSUING'
  AND q.updated_at < now() - interval '10 minutes'
  AND NOT EXISTS (SELECT 1 FROM issuance_record r WHERE r.quote_id = q.quote_id)
ORDER BY q.updated_at;
```

A quote that has an `issuance_record` row was issued, and only the final lifecycle write is missing. The status route already reports it `ISSUED`, and a retry with the same outputs replays the signatures. Close it with `UPDATE mint_quote SET lifecycle_state = 'ISSUED' WHERE quote_id = :id AND lifecycle_state = 'ISSUING'`.

To confirm the cause of a quote with no ledger row, search the mint log around its `updated_at` for the quote id and `value too long for type character varying(64)`.

## Resolve them

The payer paid and received nothing, but the mint did sign their outputs. What is safe depends on whether those signatures can still reach the wallet.

1. **Can NUT-09 restore return them?** It can only if the mint that stranded the quote stored signatures durably, in the `blind_signature` table (cashu-mint#491). If it did, have the wallet run a restore for its outputs. The payer then has their ecash, and the quote can be closed as above.
2. **If the signatures are provably unrecoverable**, the payment can be honoured again: `UPDATE mint_quote SET lifecycle_state = 'PAID' WHERE quote_id = :id AND lifecycle_state = 'ISSUING'`. The wallet's next mint request issues normally.
3. **If you cannot tell**, do not move the quote back to `PAID`. A wallet that restored the first signatures and then minted again would hold the value twice. Move the quote to `FAILED` instead, and refund the payer out of band.

Record each quote id, the option taken and the reason in the incident log.

## Related

- [Melt saga states](melt-saga-states.md): the equivalent operator states on the melt side.
- [REST API reference](../reference/rest-api.md): what the status routes report for each state.
