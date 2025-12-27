ALTER TABLE outbox_event ADD COLUMN event_id VARCHAR(256);

CREATE INDEX idx_outbox_event_id ON outbox_event (event_id);
