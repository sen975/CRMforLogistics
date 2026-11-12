CREATE TABLE whatsapp_authorization_phone_candidates (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id uuid NOT NULL REFERENCES whatsapp_authorization_attempts(id) ON DELETE CASCADE,
    token_hash varchar(128) NOT NULL,
    phone_number varchar(32) NOT NULL,
    masked_phone varchar(32) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (attempt_id, token_hash),
    UNIQUE (attempt_id, phone_number)
);

CREATE INDEX ix_whatsapp_authorization_phone_candidates_expiry
    ON whatsapp_authorization_phone_candidates(expires_at);
