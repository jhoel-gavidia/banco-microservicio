CREATE TABLE idempotency_keys (
                                  customer_id     UUID         NOT NULL,
                                  idempotency_key VARCHAR(100) NOT NULL,
                                  request_hash    VARCHAR(64)  NOT NULL,
                                  status          VARCHAR(12)  NOT NULL DEFAULT 'IN_PROGRESS',
                                  response_status INTEGER,
                                  response_body   TEXT,
                                  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

                                  PRIMARY KEY (customer_id, idempotency_key),
                                  CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);