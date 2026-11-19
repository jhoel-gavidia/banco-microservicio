CREATE TABLE transfers (
    id                  UUID          PRIMARY KEY,
    customer_id         UUID          NOT NULL,
    source_account_id   UUID          NOT NULL,
    target_account_id   UUID          NOT NULL,
    amount              NUMERIC(19,4) NOT NULL,
    currency            VARCHAR(3)    NOT NULL,
    status              VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    reference           VARCHAR(100)  NOT NULL,
    failure_reason      VARCHAR(255),
    version             BIGINT        NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_transfers_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_transfers_accounts_different CHECK (source_account_id <> target_account_id),
    CONSTRAINT ck_transfers_status CHECK (status IN (
        'PENDING', 'DEBITED', 'COMPLETING', 'COMPENSATING',
        'COMPLETED', 'COMPENSATED', 'FAILED'
    ))
);

CREATE INDEX idx_transfers_customer ON transfers (customer_id, created_at DESC);

CREATE INDEX idx_transfers_pending
    ON transfers (status, created_at)
    WHERE status IN ('PENDING', 'DEBITED', 'COMPLETING', 'COMPENSATING');
