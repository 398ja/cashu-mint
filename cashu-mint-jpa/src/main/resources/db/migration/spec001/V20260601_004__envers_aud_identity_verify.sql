-- Spec 004 T033 — pre-flight assertion that Envers _aud shadow tables
-- have matching customer_id / merchant_id columns. Pure verification,
-- no DDL — Envers auto-creates these alongside the live tables, but
-- we want a hard fail at deploy time if the shadow drifted.
--
-- The retention purge (FR-003) updates the live row AND every _aud
-- revision in the same transaction; if a shadow column is missing,
-- the purge UPDATE silently affects only the live row and the
-- shadow keeps the raw npub forever. Catching the drift here means
-- the purge implementation can rely on the columns existing.

DO $$
DECLARE
    missing_columns text[] := ARRAY[]::text[];
BEGIN
    -- voucher_quote_aud must have customer_id + merchant_id
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'voucher_quote_aud' AND column_name = 'customer_id') THEN
        missing_columns := missing_columns || 'voucher_quote_aud.customer_id';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'voucher_quote_aud' AND column_name = 'merchant_id') THEN
        missing_columns := missing_columns || 'voucher_quote_aud.merchant_id';
    END IF;

    -- customer_payment_funding_aud must have customer_id
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'customer_payment_funding_aud' AND column_name = 'customer_id') THEN
        missing_columns := missing_columns || 'customer_payment_funding_aud.customer_id';
    END IF;

    -- merchant_debit_funding_aud must have merchant_id
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'merchant_debit_funding_aud' AND column_name = 'merchant_id') THEN
        missing_columns := missing_columns || 'merchant_debit_funding_aud.merchant_id';
    END IF;

    -- merchant_iou_funding_aud must have merchant_id
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_name = 'merchant_iou_funding_aud' AND column_name = 'merchant_id') THEN
        missing_columns := missing_columns || 'merchant_iou_funding_aud.merchant_id';
    END IF;

    IF array_length(missing_columns, 1) IS NOT NULL THEN
        RAISE EXCEPTION 'Spec 004 envers shadow verification failed; missing columns: %',
                        array_to_string(missing_columns, ', ');
    END IF;
END
$$;
