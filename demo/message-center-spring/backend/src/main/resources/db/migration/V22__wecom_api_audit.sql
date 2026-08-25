CREATE TABLE wecom_api_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    operation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    installation_id uuid NOT NULL REFERENCES wecom_installations(id),
    actor_user_id uuid NOT NULL REFERENCES users(id),
    action varchar(100) NOT NULL,
    result varchar(20) NOT NULL,
    upstream_path varchar(256) NOT NULL,
    error_code varchar(128),
    upstream_errcode integer,
    upstream_http_status integer,
    upstream_hint varchar(128),
    trace_id varchar(128) NOT NULL,
    CONSTRAINT ck_wecom_api_audit_result
        CHECK (result IN ('accepted', 'success', 'failed', 'denied'))
);

CREATE INDEX ix_wecom_api_audit_operation
    ON wecom_api_audit (operation_id, occurred_at);

CREATE INDEX ix_wecom_api_audit_retention
    ON wecom_api_audit (occurred_at, id);

ALTER TABLE wecom_audit_retention_state
    DROP CONSTRAINT ck_wecom_audit_retention_stream;

ALTER TABLE wecom_audit_retention_state
    ADD CONSTRAINT ck_wecom_audit_retention_stream
        CHECK (stream IN ('viewer', 'authorization', 'api'));
