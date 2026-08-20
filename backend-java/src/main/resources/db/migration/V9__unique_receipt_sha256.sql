-- Keep every historical receipt row. For pre-existing exact duplicates, retain
-- the oldest import as the canonical hash owner and link later rows to it before
-- clearing only their conflicting hash value. This makes the reconciliation
-- explicit without deleting receipts, lots, images, or fraud metadata.
ALTER TABLE receipts
    ADD COLUMN duplicate_of_receipt_id integer REFERENCES receipts(id);

WITH ranked AS (
    SELECT id,
           first_value(id) OVER (PARTITION BY sha256 ORDER BY created_at, id) AS canonical_id,
           row_number() OVER (PARTITION BY sha256 ORDER BY created_at, id) AS duplicate_rank
    FROM receipts
    WHERE sha256 IS NOT NULL
)
UPDATE receipts AS receipt
SET duplicate_of_receipt_id = ranked.canonical_id,
    sha256 = NULL
FROM ranked
WHERE receipt.id = ranked.id
  AND ranked.duplicate_rank > 1;

DROP INDEX IF EXISTS idx_receipts_sha;

ALTER TABLE receipts
    ADD CONSTRAINT uq_receipts_sha256_exact UNIQUE (sha256);
