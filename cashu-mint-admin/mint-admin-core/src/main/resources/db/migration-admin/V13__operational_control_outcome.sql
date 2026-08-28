-- Operational controls record what happened, not only what was asked for.
-- Key rotation stores which keyset replaced which here, so an operator can
-- reconstruct the key history from the audit trail.

ALTER TABLE operational_controls ADD COLUMN outcome TEXT;
