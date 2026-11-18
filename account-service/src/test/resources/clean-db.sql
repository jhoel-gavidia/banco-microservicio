TRUNCATE TABLE outbox_events, ledger_entries, idempotency_keys, accounts RESTART IDENTITY CASCADE;
