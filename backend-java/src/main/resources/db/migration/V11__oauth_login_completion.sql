CREATE TABLE oauth_login_completions (
    token_hash BYTEA PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_oauth_login_completions_created_at
    ON oauth_login_completions (created_at);
