CREATE TABLE transfer_receipts (
    id                   UUID         PRIMARY KEY,
    transfer_id          UUID         NOT NULL REFERENCES transfers(id),
    customer_id          UUID         NOT NULL,
    status               VARCHAR(16)  NOT NULL,
    sha256_hex           VARCHAR(64)  NOT NULL,
    object_key           VARCHAR(512) NOT NULL,
    content_type         VARCHAR(100) NOT NULL,
    original_filename    VARCHAR(255) NOT NULL,
    size_bytes           BIGINT       NOT NULL,
    storage_bucket       VARCHAR(255) NOT NULL,
    storage_version_id   VARCHAR(255),
    attempts             INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at      TIMESTAMPTZ,
    lease_owner          UUID,
    lease_expires_at     TIMESTAMPTZ,
    last_error           VARCHAR(500),
    failure_reason       VARCHAR(64),
    confirmed_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_transfer_receipts_object_key UNIQUE (object_key),
    CONSTRAINT ck_transfer_receipts_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'FAILED')),
    CONSTRAINT ck_transfer_receipts_size_positive
        CHECK (size_bytes > 0),
    CONSTRAINT ck_transfer_receipts_attempts_non_negative
        CHECK (attempts >= 0),
    CONSTRAINT ck_transfer_receipts_pending_requires_lease
        CHECK (status <> 'PENDING' OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CONSTRAINT ck_transfer_receipts_pending_attempts
        CHECK (status <> 'PENDING' OR attempts BETWEEN 1 AND 3),
    CONSTRAINT ck_transfer_receipts_pending_next_attempt
        CHECK (status <> 'PENDING' OR next_attempt_at IS NOT NULL),
    CONSTRAINT ck_transfer_receipts_active_requires_confirmation
        CHECK (status <> 'ACTIVE' OR confirmed_at IS NOT NULL),
    CONSTRAINT ck_transfer_receipts_active_clears_failure
        CHECK (status <> 'ACTIVE' OR (failure_reason IS NULL AND last_error IS NULL)),
    CONSTRAINT ck_transfer_receipts_active_clears_lease
        CHECK (status <> 'ACTIVE' OR (lease_owner IS NULL AND lease_expires_at IS NULL AND next_attempt_at IS NULL)),
    CONSTRAINT ck_transfer_receipts_failed_requires_reason
        CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL),
    CONSTRAINT ck_transfer_receipts_failed_clears_lease
        CHECK (status <> 'FAILED' OR (lease_owner IS NULL AND lease_expires_at IS NULL AND next_attempt_at IS NULL)),
    CONSTRAINT ck_transfer_receipts_failure_reason
        CHECK (failure_reason IN ('UPLOAD_ERROR', 'CHECKSUM_MISMATCH', 'MISSING_OBJECT', 'UNEXPECTED_ERROR')),
    CONSTRAINT ck_receipt_lease_consistency
        CHECK ((lease_owner IS NULL) = (lease_expires_at IS NULL))
);

CREATE INDEX idx_transfer_receipts_transfer_created
    ON transfer_receipts (transfer_id, created_at DESC, id DESC);

CREATE INDEX idx_transfer_receipts_claim
    ON transfer_receipts (next_attempt_at, created_at, id)
    WHERE status = 'PENDING';

CREATE INDEX idx_transfer_receipts_lease_owner
    ON transfer_receipts (lease_owner)
    WHERE lease_owner IS NOT NULL;
