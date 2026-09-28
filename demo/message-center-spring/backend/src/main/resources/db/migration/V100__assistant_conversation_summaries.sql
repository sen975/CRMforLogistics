ALTER TABLE assistant_conversation_messages
    ADD CONSTRAINT uq_assistant_conversation_messages_owner_ref
        UNIQUE (id, user_id, conversation_id);

CREATE TABLE assistant_conversation_summaries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id uuid NOT NULL,
    summary varchar(4000) NOT NULL,
    through_message_id uuid NOT NULL,
    through_created_at timestamptz NOT NULL,
    covered_message_count integer NOT NULL,
    version bigint NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_assistant_conversation_summaries_owner_conversation
        UNIQUE (user_id, conversation_id),
    CONSTRAINT fk_assistant_conversation_summaries_cursor_owner
        FOREIGN KEY (through_message_id, user_id, conversation_id)
        REFERENCES assistant_conversation_messages (id, user_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT ck_assistant_conversation_summaries_summary
        CHECK (length(btrim(summary)) BETWEEN 1 AND 4000),
    CONSTRAINT ck_assistant_conversation_summaries_version
        CHECK (version > 0),
    CONSTRAINT ck_assistant_conversation_summaries_covered_count
        CHECK (covered_message_count > 0)
);

CREATE INDEX ix_assistant_conversation_summaries_cursor
    ON assistant_conversation_summaries (user_id, conversation_id, through_created_at, through_message_id);

COMMENT ON TABLE assistant_conversation_summaries IS
    'Rebuildable rolling context projection; the append-only assistant_conversation_messages remain the transcript source of truth';
COMMENT ON COLUMN assistant_conversation_summaries.through_message_id IS
    'Last transcript message included in summary; paired with through_created_at for stable keyset ordering';
COMMENT ON COLUMN assistant_conversation_summaries.version IS
    'Optimistic fencing version for concurrent summary updates';
COMMENT ON COLUMN assistant_conversation_summaries.covered_message_count IS
    'Exact number of persisted transcript rows represented by this summary';
