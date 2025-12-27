-- Allow multiple outbox relays (e.g., Kafka domain events + RabbitMQ work queue jobs)
-- to coexist safely without double-publishing.

ALTER TABLE outbox_event
  ADD COLUMN destination_type VARCHAR(16) NOT NULL DEFAULT 'KAFKA';

CREATE INDEX idx_outbox_destination_status_next_attempt
  ON outbox_event (destination_type, status, next_attempt_at, created_at);
