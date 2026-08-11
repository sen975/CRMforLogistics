CREATE TABLE wecom_installations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    suite_id varchar(128) NOT NULL,
    auth_corp_id varchar(128) NOT NULL,
    agent_id varchar(32) NOT NULL,
    permanent_code varchar(512) NOT NULL,
    auth_status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    authorized_at timestamptz NOT NULL DEFAULT now(),
    last_suite_ticket_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_wecom_installation UNIQUE (suite_id, auth_corp_id),
    CONSTRAINT ck_wecom_installation_status
        CHECK (auth_status IN ('ACTIVE', 'REVOKED', 'FAILED'))
);

CREATE INDEX idx_wecom_installation_auth_corp
    ON wecom_installations (auth_corp_id, auth_status);
