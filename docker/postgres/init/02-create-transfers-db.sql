-- Cuenta de servicio de transfer-service. Solo puede conectar a transfers_db.
CREATE USER transfers WITH PASSWORD 'transfers';

CREATE DATABASE transfers_db OWNER transfers;
GRANT ALL PRIVILEGES ON DATABASE transfers_db TO transfers;

-- El usuario de transferencias no debe ver otras bases del entorno local.
REVOKE ALL PRIVILEGES ON DATABASE accounts_db FROM PUBLIC;
REVOKE ALL PRIVILEGES ON DATABASE accounts_db FROM transfers;
REVOKE ALL PRIVILEGES ON DATABASE keycloak_db FROM PUBLIC;
REVOKE ALL PRIVILEGES ON DATABASE keycloak_db FROM transfers;
REVOKE ALL PRIVILEGES ON DATABASE postgres FROM transfers;
REVOKE ALL PRIVILEGES ON DATABASE template1 FROM transfers;

\connect transfers_db

ALTER SCHEMA public OWNER TO transfers;
GRANT ALL ON SCHEMA public TO transfers;
REVOKE ALL ON SCHEMA public FROM PUBLIC;