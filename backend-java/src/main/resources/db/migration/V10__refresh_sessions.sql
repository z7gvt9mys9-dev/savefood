CREATE TABLE refresh_sessions (
    id bigserial PRIMARY KEY,
    session_id uuid NOT NULL,
    user_id integer NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash bytea NOT NULL UNIQUE,
    created_at timestamp with time zone NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    revoked_at timestamp with time zone,
    CONSTRAINT refresh_sessions_expiry_after_creation CHECK (expires_at > created_at)
);

CREATE INDEX ix_refresh_sessions_user_session
    ON refresh_sessions (user_id, session_id);
CREATE INDEX ix_refresh_sessions_expires_at
    ON refresh_sessions (expires_at);
CREATE INDEX ix_refresh_sessions_active_session
    ON refresh_sessions (session_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;
CREATE INDEX ix_refresh_sessions_consumed_at
    ON refresh_sessions (consumed_at)
    WHERE consumed_at IS NOT NULL;
CREATE INDEX ix_refresh_sessions_revoked_at
    ON refresh_sessions (revoked_at)
    WHERE revoked_at IS NOT NULL;
