CREATE TABLE ledger_entries (
                                id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
                                account_id    UUID           NOT NULL REFERENCES accounts (id),
                                type          VARCHAR(6)     NOT NULL,
                                amount        NUMERIC(19, 4) NOT NULL,
                                balance_after NUMERIC(19, 4) NOT NULL,
                                reference     VARCHAR(100)   NOT NULL,
                                created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),

                                CONSTRAINT ck_ledger_type               CHECK (type IN ('DEBIT', 'CREDIT')),
                                CONSTRAINT ck_ledger_amount_positive    CHECK (amount > 0),
                                CONSTRAINT ck_ledger_balance_after_non_neg CHECK (balance_after >= 0),
                                CONSTRAINT uq_ledger_account_ref_type   UNIQUE (account_id, reference, type)
);

CREATE INDEX idx_ledger_account_created ON ledger_entries (account_id, created_at DESC);

CREATE FUNCTION prevent_ledger_modification() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries es inmutable (operacion: %)', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_ledger_modification();