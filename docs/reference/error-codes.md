# Error Codes

This reference documents all error codes returned by the cashu-mint REST API. Error responses follow a standard JSON shape and are produced by `ErrorResponse` in the protocol layer.

## Response Format

All errors are returned as JSON with a `code` and `message` field:

```json
{
  "code": "verify_proof_already_used_error",
  "message": "Proof already used"
}
```

The HTTP status code depends on the error category:

| HTTP Status | When |
|-------------|------|
| 400 | Validation errors, invalid input |
| 402 | Invoice not paid (Lightning payment required) |
| 404 | Resource not found (mint, keyset, quote) |
| 500 | Internal server error, unexpected failures |

## Mint Errors (NUT-04)

| Code | Message | HTTP | Cause | Resolution |
|------|---------|------|-------|------------|
| `mint_invoice_not_paid_error` | Invoice not paid | 402 | The Lightning invoice for the mint quote has not been paid yet. | Pay the invoice and retry, or wait for payment confirmation. |
| `mint_amount_mismatch` | Mint amount mismatch | 400 | The total amount of blinded messages does not match the quote amount. | Ensure outputs sum to the quoted amount. |
| `mint_request_missing_outputs` | Missing outputs | 400 | The mint request contains no blinded messages. | Include at least one blinded message in the `outputs` array. |
| `mint_request_contains_null_output` | Null output | 400 | One or more blinded messages in the request are null. | Remove null entries from the `outputs` array. |
| `missing_keyset_id` | Missing keyset ID | 400 | A blinded message is missing its `keyset_id` field. | Set `keyset_id` on every blinded message. |
| `invalid_output_amount` | Invalid output amount | 400 | A blinded message has a non-positive amount. | Use positive denomination amounts only. |
| `invalid_denominations` | Invalid denominations | 400 | An output amount is not a denomination the keyset holds a key for. Any combination of valid denominations that sums to the quote amount is accepted; the split need not be minimal. | Use amounts listed for the keyset in `GET /v1/keys`. |
| `keyset_not_found` | Keyset not found | 404 | The referenced keyset ID does not exist or is inactive. | Use an active keyset ID from `GET /v1/keysets`. |
| `too_many_outputs` | Maximum N outputs allowed | 400 | The number of blinded messages exceeds the configured limit. | Reduce the number of outputs. |

## Melt Errors (NUT-05)

| Code | Message | HTTP | Cause | Resolution |
|------|---------|------|-------|------------|
| `melt_proof_verification_error` | Invalid proof | 400 | One or more proofs failed cryptographic verification. | Ensure proofs are valid and signed by an active keyset. |
| `melt_proof_amount_error` | Proof amount error | 400 | The total proof amount is insufficient for the melt quote plus fees. | Provide proofs with sufficient total value. |
| `melt_invoice_not_paid_error` | Invoice not paid | 402 | The outgoing Lightning payment failed or has not settled. | Retry the melt or check the payment status. |
| `melt_proof_pending_error` | Failed to persist pending proof state | 500 | The mint could not mark proofs as pending before payment. | Retry the request. If persistent, check vault connectivity. |
| `voucher_not_accepted` | Vouchers cannot be melted at mint (Model B) | 400 | Voucher proofs were submitted to a melt endpoint. | Vouchers can only be swapped, not melted. Use `POST /v1/swap` instead. |

## Swap Errors (NUT-03)

| Code | Message | HTTP | Cause | Resolution |
|------|---------|------|-------|------------|
| `validate_amounts_error` | Input amounts do not match outputs | 400 | The sum of input proof amounts does not equal the sum of output amounts (accounting for fees). | Adjust outputs to match inputs minus fees. |
| `validate_fees_error` | Fees validation failed | 400 | The fee calculation for the swap is incorrect. | Ensure the fee deducted matches the expected keyset fee. |
| `swap_mint_not_found` | Mint not found | 404 | The mint ID inferred from proof keyset IDs does not exist. | Use proofs from a valid, active mint. |
| `too_many_inputs` | Maximum N inputs allowed | 400 | The number of input proofs exceeds the configured limit. | Reduce the number of input proofs. |
| `too_many_outputs` | Maximum N outputs allowed | 400 | The number of output blinded messages exceeds the configured limit. | Reduce the number of outputs. |
| `mixed_proof_types_error` | Cannot mix voucher and regular proofs in same operation | 400 | The inputs contain both voucher and non-voucher proofs. | Submit voucher and regular proofs in separate swap requests. |
| `voucher_split_amount_mismatch` | Voucher split amounts must match | 400 | Voucher swap output total does not equal the input total. | Ensure output amounts sum exactly to the voucher input amount. |
| `unsupported_proof_type` | Unsupported proof type for swap | 400 | A proof uses an unrecognized spending condition type. | Use only supported proof types (regular, P2PK, HTLC, voucher). |

### Shared protocol validations

Raised by `ValidateTransactionTask` on swap, mint and melt alike, before any
blinded message is signed. The numeric codes are those of
[error_codes.md](https://github.com/cashubtc/nuts/blob/main/error_codes.md).

| Code | Spec code | Message | HTTP | Cause | Resolution |
|------|-----------|---------|------|-------|------------|
| `duplicate_inputs` | 11007 | Duplicate inputs provided | 400 | The same proof appears more than once in `inputs`. | Send each proof once. |
| `duplicate_outputs` | 11008 | Duplicate outputs provided | 400 | The same blinded message appears more than once in `outputs`. | Blind each output with its own secret and blinding factor. |
| `multiple_units` | 11009 | Inputs/Outputs of multiple units | 400 | The inputs, or the outputs, span more than one unit. | Send one unit per transaction. |
| `inputs_outputs_unit_mismatch` | 11010 | Inputs and outputs not of same unit | 400 | The outputs are in a different unit from the inputs. | Match the output unit to the input unit. |
| `keyset_inactive` | 12002 | Keyset is inactive, cannot sign | 400 | An output names a keyset the mint has retired. Inputs from a retired keyset remain spendable. | Re-read `GET /v1/keys` and target an active keyset. |
| `outputs_already_signed` | 11003 | Outputs already signed | 400 | The output set was signed by an earlier request. | Recover the signatures with `POST /v1/restore` (NUT-09) instead of re-sending. |
| `transaction_not_balanced` | 11005 | Transaction is not balanced | 400 | `sum(inputs) - fees != sum(outputs)`. | Subtract the keyset fees from the outputs, per NUT-02. |

## Proof Verification Errors (NUT-07)

| Code | Message | HTTP | Cause | Resolution |
|------|---------|------|-------|------------|
| `verify_proof_already_used_error` | Proof already used | 400 | The proof's secret has already been spent. | Use fresh, unspent proofs. |
| `verify_proof_key_set_not_found` | Keyset not found | 404 | The proof references a keyset that does not exist. | Use proofs signed by a known keyset. |
| `verify_proof_key_set_id_error` | Keyset id missing | 400 | The proof is missing its keyset ID. | Include the `id` field in every proof. |
| `verify_proof_failed_error` | Proof verification failed | 400 | The proof's cryptographic signature is invalid. | Ensure the proof was correctly signed by the mint. |

## Spending Condition Errors

| Code | Message | HTTP | Cause | Resolution |
|------|---------|------|-------|------------|
| `verify_invalid_number_of_signatures` | Insufficient valid signatures | 400 | A P2PK proof does not have enough valid signatures. | Sign the proof with the required number of keys. |
| `verify_locktime_not_reached` | Locktime not reached | 400 | A time-locked proof was submitted before its locktime expired. | Wait until the locktime passes before spending. |
| `verify_invalid_refund_signature` | Invalid refund signature | 400 | The refund signature on a P2PK proof is invalid. | Provide a valid refund signature. |

## Voucher Errors

| Code | Message | HTTP | Cause | Resolution |
|------|---------|------|-------|------------|
| `voucher_expired` | Voucher has expired and cannot be redeemed | 400 | The voucher's expiry date has passed. | The voucher is no longer valid. |
| `voucher_signature_invalid` | Voucher issuer signature verification failed | 400 | The voucher's issuer signature does not verify. | Ensure the voucher was issued by a trusted issuer. |
| `voucher_master_secret_missing` | Voucher mode requires a master secret for key derivation | 500 | The mint is configured for vouchers but missing the master secret. | Set the voucher master secret in mint configuration. |

## Signature Errors

| Code | Message | HTTP | Cause | Resolution |
|------|---------|------|-------|------------|
| `sign_private_key_not_found` | Private key not found | 500 | The mint cannot find the private key for the requested denomination. | Ensure the keyset is properly loaded. |
| `invalid_blind_signature` | Invalid signature | 500 | The generated blind signature failed validation. | Retry the request. If persistent, report as a bug. |

## Internal Errors

| Code | Message | HTTP | Cause | Resolution |
|------|---------|------|-------|------------|
| `internal_error` | Internal server error | 500 | An unexpected error occurred. | Check server logs for details. |
| `store_invalid_arguments` | Store invalid arguments | 500 | Invalid arguments passed to signature storage. | This indicates a bug; report it. |
| `retrieve_invalid_arguments` | Retrieve invalid arguments | 500 | Invalid arguments passed to signature retrieval. | This indicates a bug; report it. |

## See Also

- [REST API reference](rest-api.md)
- [Glossary](glossary.md)
- [Supported NUTs](nuts.md)
