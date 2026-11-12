CREATE TABLE assistant_conversations (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    id uuid NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE',
    created_at timestamptz NOT NULL DEFAULT now(),
    last_activity_at timestamptz NOT NULL DEFAULT now(),
    archived_at timestamptz,
    expired_at timestamptz,
    deleted_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_assistant_conversations_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED', 'EXPIRED', 'DELETED')),
    CONSTRAINT ck_assistant_conversations_terminal_timestamps
        CHECK ((status <> 'EXPIRED' OR expired_at IS NOT NULL)
            AND (status <> 'DELETED' OR deleted_at IS NOT NULL)),
    PRIMARY KEY (user_id, id)
);

-- V85 created transcript rows before the lifecycle owner existed. Rebuild one
-- lifecycle row per existing (user, conversation) before adding constraints.
-- Only the most recently active conversation per user remains ACTIVE so the
-- partial unique index below can be created without collapsing history.
INSERT INTO assistant_conversations
    (id, user_id, status, created_at, last_activity_at, archived_at, updated_at)
SELECT conversation_id,
       user_id,
       CASE WHEN row_number() OVER (
                    PARTITION BY user_id
                    ORDER BY max(created_at) DESC, conversation_id DESC
                ) = 1 THEN 'ACTIVE' ELSE 'ARCHIVED' END,
       min(created_at),
       max(created_at),
       CASE WHEN row_number() OVER (
                    PARTITION BY user_id
                    ORDER BY max(created_at) DESC, conversation_id DESC
                ) = 1 THEN NULL ELSE max(created_at) END,
       max(created_at)
FROM assistant_conversation_messages
GROUP BY user_id, conversation_id;

CREATE UNIQUE INDEX uq_assistant_conversations_one_active
    ON assistant_conversations (user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_assistant_conversations_visible
    ON assistant_conversations (user_id, status, last_activity_at DESC, id DESC);

ALTER TABLE assistant_conversation_messages
    ADD CONSTRAINT fk_assistant_conversation_messages_lifecycle
    FOREIGN KEY (user_id, conversation_id) REFERENCES assistant_conversations (user_id, id) ON DELETE CASCADE;

COMMENT ON TABLE assistant_conversations IS
    'Lifecycle owner for assistant conversations; transcript rows remain append-only';
