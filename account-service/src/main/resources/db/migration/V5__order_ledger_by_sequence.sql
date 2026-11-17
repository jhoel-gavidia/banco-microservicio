CREATE SEQUENCE ledger_entry_seq;

ALTER TABLE ledger_entries
    ADD COLUMN entry_seq BIGINT NOT NULL DEFAULT nextval('ledger_entry_seq');

ALTER SEQUENCE ledger_entry_seq OWNED BY ledger_entries.entry_seq;

CREATE UNIQUE INDEX uq_ledger_entry_seq ON ledger_entries (entry_seq);

ALTER TABLE ledger_entries
    ALTER COLUMN created_at SET DEFAULT clock_timestamp();

DROP INDEX idx_ledger_account_created;

CREATE INDEX idx_ledger_account_seq ON ledger_entries (account_id, entry_seq DESC);
