-- Existing files cannot be measured from PostgreSQL. Charge every legacy row at
-- the maximum accepted upload size so rollout is conservative until it is
-- claimed or cleaned.
ALTER TABLE public.shop_lot_photo_uploads
    ADD COLUMN byte_size bigint NOT NULL DEFAULT 5242880,
    ADD COLUMN expires_at timestamp with time zone,
    ADD COLUMN cleanup_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN cleanup_next_attempt_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN cleanup_last_error text,
    ADD CONSTRAINT shop_lot_photo_uploads_byte_size_positive CHECK (byte_size > 0);

UPDATE public.shop_lot_photo_uploads
SET expires_at = created_at + INTERVAL '45 minutes'
WHERE expires_at IS NULL;

ALTER TABLE public.shop_lot_photo_uploads
    ALTER COLUMN expires_at SET NOT NULL,
    ALTER COLUMN expires_at SET DEFAULT (CURRENT_TIMESTAMP + INTERVAL '45 minutes');

CREATE INDEX shop_lot_photo_uploads_pending_quota_idx
    ON public.shop_lot_photo_uploads (shop_id) INCLUDE (byte_size)
    WHERE lot_id IS NULL;

CREATE INDEX shop_lot_photo_uploads_stale_cleanup_idx
    ON public.shop_lot_photo_uploads (cleanup_next_attempt_at, expires_at, filename)
    WHERE lot_id IS NULL;
