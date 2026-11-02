-- Which pod last claimed the row. Not used for correctness, only to see the work being shared.
ALTER TABLE webhook_deliveries ADD COLUMN worker_id TEXT;
ALTER TABLE outbound_messages ADD COLUMN worker_id TEXT;
