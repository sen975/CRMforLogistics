CREATE TABLE wecom_chatdata_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    msgid varchar(256) NOT NULL,
    secret_key varchar(512) NOT NULL,
    external_userid varchar(128) NOT NULL,
    userid varchar(128) NOT NULL,
    send_time bigint NOT NULL,
    msgtype varchar(32) NOT NULL,
    direction varchar(16) NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_wecom_chatdata_msg UNIQUE (msgid, userid, external_userid),
    CONSTRAINT ck_wecom_chatdata_direction CHECK (direction IN ('', 'inbound', 'outbound')),
    CONSTRAINT ck_wecom_chatdata_send_time CHECK (send_time >= 0)
);

CREATE INDEX idx_wecom_chatdata_messages_time
    ON wecom_chatdata_messages (send_time);

CREATE TABLE wecom_chatdata_cursor (
    cursor_key varchar(64) PRIMARY KEY,
    cursor_value varchar(128) NOT NULL DEFAULT '',
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE wecom_public_key_registration (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    auth_corp_id varchar(128) NOT NULL,
    public_key_version integer NOT NULL,
    public_key_sha256 varchar(64) NOT NULL,
    registered_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_wecom_public_key_reg UNIQUE (auth_corp_id, public_key_version)
);
