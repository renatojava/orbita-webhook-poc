--liquibase formatted sql
--changeset orbita:002-reply-windows
-- One row per contact with an open grouping window; it is deleted when the window closes.
-- Being a table, an open window survives a crash and still gets its reply after restart.
CREATE TABLE reply_windows (
    tenant_id       TEXT NOT NULL REFERENCES tenants (id),
    contact_phone   TEXT NOT NULL,
    opened_at       TIMESTAMPTZ NOT NULL,
    last_message_at TIMESTAMPTZ NOT NULL,
    due_at          TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, contact_phone)
);
CREATE INDEX reply_windows_due_idx ON reply_windows (due_at);

-- Which reply answered this message. NULL means it is still waiting for its window to close.
ALTER TABLE messages ADD COLUMN reply_key TEXT;
CREATE INDEX messages_unanswered_idx ON messages (tenant_id, from_phone, id) WHERE reply_key IS NULL;
