CREATE TABLE conversation_preferences (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_type varchar(20) NOT NULL,
    target_id uuid NOT NULL,
    pinned boolean NOT NULL DEFAULT false,
    hidden_at timestamptz,
    sort_rank bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, target_type, target_id),
    CONSTRAINT ck_conversation_preferences_target_type CHECK (target_type IN ('CONTACT', 'WECOM_GROUP'))
);

CREATE INDEX ix_conversation_preferences_order
    ON conversation_preferences (user_id, pinned DESC, sort_rank NULLS LAST, updated_at DESC);
