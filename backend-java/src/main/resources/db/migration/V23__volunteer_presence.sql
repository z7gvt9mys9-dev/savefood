ALTER TABLE volunteers
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_volunteers_presence_city
    ON volunteers (city, last_seen_at DESC);
