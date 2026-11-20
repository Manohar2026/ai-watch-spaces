-- V4: durable plain-text room chat for the non-AI MVP.
CREATE TABLE chat_messages (
    id UUID PRIMARY KEY,
    watch_space_id UUID NOT NULL REFERENCES watch_spaces(id) ON DELETE RESTRICT,
    room_event_id UUID NOT NULL UNIQUE REFERENCES room_events(id) ON DELETE RESTRICT,
    user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE RESTRICT,
    message_type VARCHAR(16) NOT NULL DEFAULT 'CHAT' CHECK (message_type = 'CHAT'),
    body VARCHAR(2000) NOT NULL CHECK (length(body) BETWEEN 1 AND 2000),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_chat_messages_room_created ON chat_messages(watch_space_id, created_at DESC, id DESC);

