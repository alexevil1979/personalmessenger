ALTER TABLE sessions
    ADD COLUMN access_token_hash text,
    ADD COLUMN access_expires_at timestamptz;

CREATE INDEX sessions_refresh_hash_idx ON sessions (refresh_token_hash);
CREATE UNIQUE INDEX sessions_access_hash_active_idx
    ON sessions (access_token_hash)
    WHERE access_token_hash IS NOT NULL AND revoked_at IS NULL;
