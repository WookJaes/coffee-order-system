ALTER TABLE order_events
    DROP CHECK chk_order_events_status,
    ADD COLUMN processing_started_at DATETIME NULL AFTER retry_count,
    ADD COLUMN next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER processing_started_at,
    ADD COLUMN processing_token VARCHAR(36) NULL AFTER next_attempt_at,
    ADD COLUMN last_error VARCHAR(1000) NULL AFTER processing_token,
    ADD CONSTRAINT chk_order_events_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED'));

CREATE INDEX idx_order_events_publishable ON order_events (status, next_attempt_at, id);
CREATE INDEX idx_order_events_processing_token ON order_events (processing_token);
