-- Telegram login uses two distinct credentials:
--   token                 — status-only transaction token returned to the browser;
--   completion_token_hash — one-time credential delivered in the private bot chat.
-- Store only a SHA-256 hash of the completion credential so a database read cannot
-- turn a pending/confirmed transaction into a browser session.
ALTER TABLE telegram_login_tokens
    ADD COLUMN completion_user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    ADD COLUMN completion_token_hash TEXT,
    ADD COLUMN completion_created_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN completion_delivered_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN confirmed_chat_id TEXT;

-- Invalidate any confirmation created by the legacy direct-poll protocol and
-- prevent an older bot instance from making an initial token JWT-bearing again.
-- Keeping the column (rather than dropping it) makes an older poller see pending.
UPDATE telegram_login_tokens SET user_id = NULL WHERE user_id IS NOT NULL;

ALTER TABLE telegram_login_tokens
    ADD CONSTRAINT ck_telegram_login_initial_token_status_only CHECK (user_id IS NULL);

CREATE UNIQUE INDEX uq_telegram_login_completion_hash
    ON telegram_login_tokens (completion_token_hash)
    WHERE completion_token_hash IS NOT NULL;
