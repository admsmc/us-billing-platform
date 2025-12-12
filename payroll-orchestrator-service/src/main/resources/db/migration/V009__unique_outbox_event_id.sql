-- Enforce idempotency for deterministic events by making event_id unique.
-- Multiple NULLs are allowed by SQL UNIQUE semantics, so this does not affect rows that omit event_id.
ALTER TABLE outbox_event ADD CONSTRAINT uq_outbox_event_event_id UNIQUE (event_id);
