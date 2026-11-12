CREATE TABLE wecom_external_group_syncs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    installation_id uuid NOT NULL REFERENCES wecom_installations(id) ON DELETE CASCADE,
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    cursor varchar(1024) NOT NULL DEFAULT '',
    page_count integer NOT NULL DEFAULT 0,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_owner varchar(100),
    lease_until timestamptz,
    error_code varchar(100),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CONSTRAINT ck_wecom_external_group_sync_status
        CHECK (status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_wecom_external_group_sync_page_count
        CHECK (page_count >= 0 AND page_count <= 1000),
    CONSTRAINT ck_wecom_external_group_sync_attempt_count
        CHECK (attempt_count >= 0 AND attempt_count <= 10)
);

CREATE TABLE wecom_external_group_sync_items (
    sync_id uuid NOT NULL REFERENCES wecom_external_group_syncs(id) ON DELETE CASCADE,
    chat_id varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (sync_id, chat_id)
);

CREATE UNIQUE INDEX ux_wecom_external_group_sync_active
    ON wecom_external_group_syncs(installation_id)
    WHERE status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT');

CREATE INDEX ix_wecom_external_group_sync_runnable
    ON wecom_external_group_syncs(next_attempt_at, created_at, id)
    WHERE status IN ('PENDING', 'RETRY_WAIT');
