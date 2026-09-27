-- cashu-mint#519: record which request took each swap hold.
--
-- The input lock that serialises a swap and its NUT-19 replay only covers one
-- JVM. A replay reaching a second instance while its original is still signing
-- misses the response cache and is refused by the original's hold. With the
-- request fingerprint on the hold, that instance can tell "my original is in
-- flight" from "my inputs are held by a different request", and wait for the
-- original's response in the first case only.
--
-- The column is the same SHA-256 (hex) used as swap_response_cache's key.
-- Nullable because holds written before this migration carry none; such a
-- hold is simply never waited on.

ALTER TABLE swap_hold ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);

-- A replay's only query: every hold taken by the same request.
CREATE INDEX IF NOT EXISTS ix_swap_hold_request_fingerprint
    ON swap_hold (request_fingerprint);
