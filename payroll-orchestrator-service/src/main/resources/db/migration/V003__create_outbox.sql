CREATE TABLE outbox_event (
  outbox_id VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,

  topic VARCHAR(256) NOT NULL,
  event_key VARCHAR(256) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  aggregate_id VARCHAR(256),

  payload_json CLOB NOT NULL,

  attempts INT DEFAULT 0 NOT NULL,
  next_attempt_at TIMESTAMP,
  last_error VARCHAR(2000),

  locked_by VARCHAR(128),
  locked_at TIMESTAMP,

  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  published_at TIMESTAMP,

  PRIMARY KEY (outbox_id)
);

CREATE INDEX idx_outbox_status_next_attempt ON outbox_event (status, next_attempt_at, created_at);
CREATE INDEX idx_outbox_locked ON outbox_event (locked_by, locked_at);
