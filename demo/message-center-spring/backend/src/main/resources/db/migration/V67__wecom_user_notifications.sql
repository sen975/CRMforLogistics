CREATE TABLE wecom_user_notifications (
    id uuid PRIMARY KEY,
    conversation_id uuid NOT NULL,
    channel_account_id uuid NOT NULL,
    recipient_user_id uuid NOT NULL,
    recipient_wecom_user_id varchar(128) NOT NULL,
    auth_corp_id varchar(128) NOT NULL,
    agent_id varchar(64) NOT NULL,
    channel_type varchar(32) NOT NULL,
    contact_label varchar(200) NOT NULL,
    message_count integer NOT NULL DEFAULT 1,
    last_preview varchar(200),
    first_message_at timestamptz NOT NULL,
    send_after timestamptz NOT NULL,
    status varchar(16) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    last_error varchar(500),
    sent_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0
);

ALTER TABLE wecom_user_notifications
    ADD CONSTRAINT ck_wecom_user_notification_status
    CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED'));

-- 同一会话同一收件人在待发状态下只能有一行，聚合 upsert 依赖这个索引。
CREATE UNIQUE INDEX ux_wecom_user_notification_pending
    ON wecom_user_notifications (conversation_id, recipient_user_id)
    WHERE status = 'PENDING';

CREATE INDEX ix_wecom_user_notification_due
    ON wecom_user_notifications (send_after)
    WHERE status = 'PENDING';
