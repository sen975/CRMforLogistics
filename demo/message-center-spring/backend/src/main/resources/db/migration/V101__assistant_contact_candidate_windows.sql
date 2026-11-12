CREATE TABLE assistant_contact_candidate_windows (
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id uuid NOT NULL,
    references_json jsonb NOT NULL,
    saved_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (user_id, conversation_id),
    CONSTRAINT ck_assistant_contact_candidate_window_refs
        CHECK (jsonb_typeof(references_json) = 'array'
            AND jsonb_array_length(references_json) BETWEEN 1 AND 20
            AND octet_length(references_json::text) <= 2048),
    CONSTRAINT ck_assistant_contact_candidate_window_expiry CHECK (expires_at > saved_at)
);

CREATE INDEX ix_assistant_contact_candidate_windows_expiry
    ON assistant_contact_candidate_windows (expires_at);

COMMENT ON TABLE assistant_contact_candidate_windows IS
    'Short-lived contact references for assistant follow-ups; restore must reauthorize through the contact owner';
