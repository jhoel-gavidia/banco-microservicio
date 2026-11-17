CREATE TABLE accounts (
                          id          UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                          customer_id UUID           NOT NULL,
                          number      VARCHAR(34)    NOT NULL,
                          currency    VARCHAR(3)     NOT NULL,
                          balance     NUMERIC(19, 4) NOT NULL DEFAULT 0,
                          status      VARCHAR(10)    NOT NULL DEFAULT 'ACTIVE',
                          version     BIGINT         NOT NULL DEFAULT 0,
                          created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),

                          CONSTRAINT uq_accounts_number           UNIQUE (number),
                          CONSTRAINT ck_accounts_balance_non_neg  CHECK (balance >= 0),
                          CONSTRAINT ck_accounts_status           CHECK (status IN ('ACTIVE', 'BLOCKED', 'CLOSED')),
                          CONSTRAINT ck_accounts_currency         CHECK (currency ~ '^[A-Z]{3}$')
    );

CREATE INDEX idx_accounts_customer_id ON accounts (customer_id);