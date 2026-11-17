-- Una Idempotency-Key se usa por cuenta, no por cliente: la misma clave en dos
-- cuentas propias son dos operaciones distintas.
-- La tabla es solo una caché de respuestas y no podemos derivar el account_id
-- de las filas anteriores (solo guardamos el hash), así que se descartan.
TRUNCATE TABLE idempotency_keys;

ALTER TABLE idempotency_keys
    ADD COLUMN account_id UUID NOT NULL;

ALTER TABLE idempotency_keys
    DROP CONSTRAINT idempotency_keys_pkey;

ALTER TABLE idempotency_keys
    ADD PRIMARY KEY (customer_id, account_id, idempotency_key);

ALTER TABLE idempotency_keys
    ADD CONSTRAINT fk_idempotency_account
    FOREIGN KEY (account_id) REFERENCES accounts (id);
