-- Spec 004 T035 / research R5b — replace verbatim merchant_iou_funding.iou_terms
-- (TEXT) with a SHA-256 content hash. The terms doc may contain
-- merchant-side commitments (pricing, settlement terms) that the
-- mint shouldn't custody. The hash is enough to prove "this is the
-- terms document that was agreed at issuance" without holding the
-- sensitive content.
--
-- SHA-256 (not HMAC-SHA-256 like the identity columns) because the
-- terms hash is content-addressed — anyone with the original
-- document can verify the match without a salt.
--
-- Migration sequence:
--   1. ADD COLUMN iou_terms_hash CHAR(64) (this file)
--   2. Backfill from existing iou_terms via encode(sha256(...), 'hex')
--   3. iou_terms TEXT column dropped in a follow-up migration once
--      backfill confirms no row has iou_terms_hash IS NULL while
--      iou_terms IS NOT NULL. Marked TODO in the entity Javadoc.

ALTER TABLE merchant_iou_funding
    ADD COLUMN iou_terms_hash VARCHAR(64);

ALTER TABLE merchant_iou_funding_aud
    ADD COLUMN iou_terms_hash VARCHAR(64);

-- Backfill existing rows. encode(...,'hex') yields lowercase hex per
-- PostgreSQL docs; matches the convention used elsewhere in spec 004.
-- For rows where iou_terms IS NULL (legitimate optional case), the
-- hash stays NULL.
UPDATE merchant_iou_funding
   SET iou_terms_hash = encode(sha256(iou_terms::bytea), 'hex')
 WHERE iou_terms IS NOT NULL
   AND iou_terms_hash IS NULL;

UPDATE merchant_iou_funding_aud
   SET iou_terms_hash = encode(sha256(iou_terms::bytea), 'hex')
 WHERE iou_terms IS NOT NULL
   AND iou_terms_hash IS NULL;
