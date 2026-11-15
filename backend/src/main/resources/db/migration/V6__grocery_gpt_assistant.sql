-- ==========================================================
-- V6: GroceryGPT AI Business Assistant
-- Conversation history & audit trail for grounded AI responses
-- ==========================================================

CREATE TABLE IF NOT EXISTS ai_conversations (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           VARCHAR(160) NOT NULL,
    last_intent     VARCHAR(60),
    last_context    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_conv_user_updated ON ai_conversations(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS ai_messages (
    id               BIGSERIAL PRIMARY KEY,
    conversation_id  BIGINT NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
    role             VARCHAR(15) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content          TEXT NOT NULL,
    intent           VARCHAR(60),
    tools_invoked    VARCHAR(255),
    structured_data  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ai_msg_conv_created ON ai_messages(conversation_id, created_at ASC);
