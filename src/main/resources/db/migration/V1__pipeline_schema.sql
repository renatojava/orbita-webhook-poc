-- A tenant is one WhatsApp number. The token bucket lives on the row, so every replica shares the same limit.
CREATE TABLE tenants (
    id                   TEXT PRIMARY KEY,
    phone_number_id      TEXT NOT NULL UNIQUE,
    send_rate_per_second DOUBLE PRECISION NOT NULL CHECK (send_rate_per_second > 0),
    send_burst           INTEGER NOT NULL CHECK (send_burst > 0),
    tokens               DOUBLE PRECISION NOT NULL,
    tokens_refilled_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The queue: one row per HTTP delivery, stored before answering 200. Duplicates are kept on purpose:
-- they are collapsed by the worker, which also makes the real duplicate rate measurable.
CREATE TABLE webhook_deliveries (
    id                 BIGSERIAL PRIMARY KEY,
    raw_body           BYTEA NOT NULL,
    status             TEXT NOT NULL DEFAULT 'PENDING'
                       CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'DEAD')),
    attempts           INTEGER NOT NULL DEFAULT 0,
    next_attempt_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until       TIMESTAMPTZ,
    last_error         TEXT,
    duplicate_messages INTEGER NOT NULL DEFAULT 0,
    received_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at        TIMESTAMPTZ
);
CREATE INDEX webhook_deliveries_ready_idx ON webhook_deliveries (next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX webhook_deliveries_lease_idx ON webhook_deliveries (locked_until) WHERE status = 'PROCESSING';
CREATE INDEX webhook_deliveries_dead_idx ON webhook_deliveries (id) WHERE status = 'DEAD';

-- Conversation history. The unique key is what guarantees one record per message under concurrent resends.
CREATE TABLE messages (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          TEXT NOT NULL REFERENCES tenants (id),
    wamid              TEXT NOT NULL,
    from_phone         TEXT NOT NULL,
    contact_name       TEXT,
    type               TEXT NOT NULL,
    body               TEXT,
    provider_timestamp TIMESTAMPTZ NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT messages_tenant_wamid_key UNIQUE (tenant_id, wamid)
);

-- Outbox: the row exists before the send is attempted, so no send can go unrecorded.
-- priority 0 = automatic reply, 1 = campaign: a tenant's campaign never delays its own replies.
CREATE TABLE outbound_messages (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           TEXT NOT NULL REFERENCES tenants (id),
    idempotency_key     TEXT NOT NULL,
    priority            SMALLINT NOT NULL,
    to_phone            TEXT NOT NULL,
    body                TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'DEAD', 'UNCERTAIN')),
    attempts            INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    locked_until        TIMESTAMPTZ,
    provider_message_id TEXT,
    last_error          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at             TIMESTAMPTZ,
    CONSTRAINT outbound_tenant_key UNIQUE (tenant_id, idempotency_key)
);
CREATE INDEX outbound_ready_idx ON outbound_messages (tenant_id, priority, id) WHERE status = 'PENDING';
CREATE INDEX outbound_lease_idx ON outbound_messages (locked_until) WHERE status = 'SENDING';
