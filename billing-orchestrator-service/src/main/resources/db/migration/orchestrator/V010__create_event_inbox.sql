CREATE TABLE event_inbox (
  consumer VARCHAR(128) NOT NULL,
  event_id VARCHAR(256) NOT NULL,
  received_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
  PRIMARY KEY (consumer, event_id)
);
