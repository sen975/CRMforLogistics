CREATE TABLE wecom_authorization_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    action varchar(64) NOT NULL,
    result varchar(20) NOT NULL,
    suite_id varchar(128) NOT NULL,
    auth_corp_id varchar(128),
    error_code varchar(128),
    upstream_errcode integer,
    upstream_path varchar(256),
    upstream_http_status integer,
    upstream_hint varchar(128),
    CONSTRAINT ck_wecom_auth_audit_result CHECK (result IN ('accepted', 'succeeded', 'failed'))
);

CREATE TABLE wecom_viewer_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    action varchar(100) NOT NULL,
    result varchar(20) NOT NULL,
    wecom_user_id varchar(128),
    contact_point_id varchar(256),
    viewer_session_id varchar(64),
    error_code varchar(128),
    upstream_errcode integer,
    upstream_path varchar(256),
    upstream_http_status integer,
    upstream_hint varchar(128),
    CONSTRAINT ck_wecom_viewer_audit_result
        CHECK (result IN ('success', 'denied', 'failed', 'rate_limited'))
);
