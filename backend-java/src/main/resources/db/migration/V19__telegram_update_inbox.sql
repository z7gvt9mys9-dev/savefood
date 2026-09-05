-- The inbox row lock and processing transaction also cover internal side effects.
CREATE TABLE public.telegram_update_inbox (
    update_id bigint PRIMARY KEY CHECK (update_id >= 0),
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    payload text NOT NULL CHECK (octet_length(payload) <= 65536),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'processing', 'processed', 'failed')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    claimed_at timestamptz,
    processed_at timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_error text
);
CREATE INDEX telegram_update_inbox_pending_idx
    ON public.telegram_update_inbox (next_attempt_at, update_id)
    WHERE status IN ('pending', 'processing');
