ALTER TABLE transfers ADD COLUMN correlation_id VARCHAR(128);

UPDATE transfers
SET correlation_id = 'legacy-' || id::text
WHERE correlation_id IS NULL;

ALTER TABLE transfers ALTER COLUMN correlation_id SET NOT NULL;
