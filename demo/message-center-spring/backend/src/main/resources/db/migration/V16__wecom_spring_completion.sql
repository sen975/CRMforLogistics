CREATE TABLE wecom_user_bindings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users(id),
    suite_id varchar(128) NOT NULL,
    auth_corp_id varchar(128) NOT NULL,
    wecom_user_id varchar(128) NOT NULL,
    provisioning_source varchar(20) NOT NULL,
    bound_at timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (user_id),
    UNIQUE (suite_id, auth_corp_id, wecom_user_id),
    CONSTRAINT ck_wecom_user_binding_source
        CHECK (provisioning_source IN ('AUTO_CREATED', 'BOUND_EXISTING'))
);

ALTER TABLE wecom_installations
    ALTER COLUMN permanent_code TYPE text;

ALTER TABLE wecom_chatdata_messages
    ALTER COLUMN secret_key TYPE text;

ALTER TABLE wecom_installations
    ADD COLUMN last_authorization_event_id varchar(80),
    ADD COLUMN last_authorization_event_at timestamptz;

ALTER TABLE wecom_authorization_audit
    DROP CONSTRAINT ck_wecom_auth_audit_result,
    ADD COLUMN event_id varchar(80),
    ADD COLUMN attempt integer,
    ADD COLUMN target_status varchar(20),
    ADD COLUMN expected_version bigint,
    ADD CONSTRAINT ck_wecom_auth_audit_result
        CHECK (result IN ('accepted', 'pending', 'succeeded', 'failed')),
    ADD CONSTRAINT ck_wecom_auth_audit_attempt
        CHECK ((event_id IS NULL AND attempt IS NULL)
            OR (event_id IS NOT NULL AND attempt >= 1));

CREATE UNIQUE INDEX ux_wecom_auth_audit_phase
    ON wecom_authorization_audit(event_id, attempt, result)
    WHERE event_id IS NOT NULL;

CREATE TABLE wecom_credential_migration_markers (
    migration_name varchar(100) PRIMARY KEY,
    completed_at timestamptz NOT NULL
);
