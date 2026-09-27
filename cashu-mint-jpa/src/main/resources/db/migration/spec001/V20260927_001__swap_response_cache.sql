-- cashu-mint#482: the NUT-19 response cache for POST /v1/swap.
--
-- A wallet that loses its connection mid-swap cannot tell "the mint spent my
-- inputs and signed outputs I never received" from "the mint never saw the
-- request". Replaying the request used to be refused with
-- outputs_already_signed, leaving the inputs spent and the outputs unknown.
-- With this table the replay returns the stored response instead.
--
-- request_fingerprint is a SHA-256 over the request's inputs AND outputs (see
-- SwapRequestFingerprint). Outputs alone would let two different swaps that
-- ask for the same outputs share a row, and the second would be handed the
-- first one's signatures.
--
-- The primary key is the only uniqueness the cache needs. Two instances
-- racing the same request cannot both sign it (the input hold and the
-- blind_signature primary key refuse the loser), so a second insert for one
-- fingerprint can only ever carry the same response, and the adapter keeps
-- the first.
--
-- expires_at is written from the advertised NUT-19 ttl. Lookups ignore
-- expired rows, and SwapResponseCachePurger deletes them on a schedule, so the
-- table holds roughly one ttl's worth of swaps.

CREATE TABLE IF NOT EXISTS swap_response_cache (
    request_fingerprint VARCHAR(64)              NOT NULL,
    response_json       JSONB                    NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT swap_response_cache_pk PRIMARY KEY (request_fingerprint),
    CONSTRAINT swap_response_cache_expiry_chk CHECK (expires_at >= created_at)
);

-- The purger's only query.
CREATE INDEX IF NOT EXISTS swap_response_cache_expires_ix ON swap_response_cache (expires_at);
