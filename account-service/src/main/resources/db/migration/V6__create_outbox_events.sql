CREATE SEQUENCE outbox_event_seq;

CREATE TABLE outbox_events (
    id               UUID           PRIMARY KEY,
    sequence_no      BIGINT         NOT NULL DEFAULT nextval('outbox_event_seq'),
    aggregate_type   VARCHAR(30)    NOT NULL,
    aggregate_id     UUID           NOT NULL,
    event_type       VARCHAR(50)    NOT NULL,
    payload          JSONB          NOT NULL,
    occurred_at      TIMESTAMPTZ    NOT NULL,
    published_at     TIMESTAMPTZ,
    attempts         INTEGER        NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_outbox_attempts_non_neg CHECK (attempts >= 0)
);

ALTER SEQUENCE outbox_event_seq OWNED BY outbox_events.sequence_no;

CREATE UNIQUE INDEX uq_outbox_sequence ON outbox_events (sequence_no);
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_id, sequence_no);
CREATE INDEX idx_outbox_pending
    ON outbox_events (next_attempt_at, sequence_no)
    WHERE published_at IS NULL;