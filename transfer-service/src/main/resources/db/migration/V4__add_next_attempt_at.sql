ALTER TABLE transfers ADD COLUMN next_attempt_at TIMESTAMPTZ;

CREATE INDEX idx_transfers_next_attempt
    ON transfers (next_attempt_at)
    WHERE next_attempt_at IS NOT NULL;
