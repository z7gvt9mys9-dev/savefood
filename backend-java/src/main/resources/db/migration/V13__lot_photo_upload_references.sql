-- A JSON lot-create request may only use a photo saved by the authenticated
-- shop through the dedicated lot-photo upload flow.  The conditional claim is
-- what makes a reference single-use even when two create requests race.
CREATE TABLE public.shop_lot_photo_uploads (
    filename text PRIMARY KEY,
    shop_id integer NOT NULL REFERENCES public.shops(id) ON DELETE CASCADE,
    -- Deliberately not a foreign key: deleting a lot must not make its upload
    -- claimable again.  This is an immutable consumed-reference marker.
    lot_id integer UNIQUE,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at timestamp with time zone,
    CONSTRAINT shop_lot_photo_uploads_filename_format
        CHECK (filename ~ '^[a-f0-9]{32}\.(jpg|jpeg|png)$')
);

CREATE INDEX shop_lot_photo_uploads_available_idx
    ON public.shop_lot_photo_uploads (shop_id, filename) WHERE lot_id IS NULL;
