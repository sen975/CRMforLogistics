-- 企业微信「客户联系事件」流水表（应用级回调通道，receiveid = 密文 corpid）。
--
-- 动机：客户联系能力此前只有 6 个查询接口，全部实时透传、不落库，于是只有「当前有哪些客户」
-- 这个快照，没有时间维度 —— 客户被删后下次拉列表就少一行，不留痕迹。本表把「状态」变成
-- 「状态 + 变化」，使「某客户何时、由谁、经哪个渠道加进来」和「何时流失」成为可回答的问题。
--
-- 可靠性契约：回调必须在 1 秒内回包（超时企微会屏蔽该事件一段时间），而先入内存队列再回包
-- 一旦进程崩溃就永久丢失。因此本表是**回包前的同步落库真源**：只有 INSERT 提交成功、
-- 或撞上 dedupe_key 唯一约束（重复投递）时，才向企微返回 success。
--
-- 幂等：企微事件 XML 没有 EventId，只有 CreateTime，所以去重键由服务层自造
-- （sha256(规范化 JSON)，见 WeComContactEventService#dedupeKey）。
CREATE TABLE wecom_contact_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 事件归属的安装记录。RESTRICT：安装记录被删说明授权链路已经出问题，
    -- 此时静默级联删掉事件流水会让缺口无法追溯。
    installation_id uuid NOT NULL REFERENCES wecom_installations (id) ON DELETE RESTRICT,
    suite_id varchar(128) NOT NULL,
    -- 密文 corpid，大小写敏感，禁止归一化（企微官方明确提示密文 ID 区分大小写）
    auth_corp_id varchar(128) NOT NULL,
    event varchar(64) NOT NULL,
    change_type varchar(64) NOT NULL,
    wecom_user_id varchar(128),
    external_user_id varchar(128),
    chat_id varchar(128),
    state varchar(256),
    welcome_code varchar(256),
    fail_reason varchar(256),
    provider_source varchar(32),
    provider_created_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL,
    dedupe_key varchar(64) NOT NULL,
    ingest_status varchar(24) NOT NULL DEFAULT 'RECEIVED',
    attempt_count integer NOT NULL DEFAULT 0,
    last_error varchar(256),
    processed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_wecom_contact_events_dedupe UNIQUE (dedupe_key),
    -- 首期只接客户关系事件；客户群/标签事件的字段合同不同，必须走独立迁移，不能靠通用列宣称支持。
    CONSTRAINT ck_wecom_contact_events_event CHECK (event = 'change_external_contact'),
    CONSTRAINT ck_wecom_contact_events_ingest_status
        CHECK (ingest_status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'RETRY', 'DEAD_LETTER')),
    CONSTRAINT ck_wecom_contact_events_attempt_count CHECK (attempt_count >= 0)
);

-- 单个客户的动态时间线。
CREATE INDEX ix_wecom_contact_events_external_user
    ON wecom_contact_events (installation_id, external_user_id, provider_created_at DESC);

-- 「最近的客户动态」列表。
CREATE INDEX ix_wecom_contact_events_recent
    ON wecom_contact_events (installation_id, provider_created_at DESC);

COMMENT ON TABLE wecom_contact_events IS
    'WeCom customer contact event stream (app-level callback channel); durable ingest source of truth';

COMMENT ON COLUMN wecom_contact_events.provider_created_at IS
    'Event CreateTime from the provider XML, not the local receive time';

COMMENT ON COLUMN wecom_contact_events.dedupe_key IS
    'sha256 of canonical JSON of all semantic identifiers; WeCom events carry no EventId';

COMMENT ON COLUMN wecom_contact_events.provider_source IS
    'del_external_contact only: DELETE_BY_TRANSFER means the relation ended by automated transfer';

COMMENT ON COLUMN wecom_contact_events.ingest_status IS
    'Ingest lifecycle; follow-up actions claim rows with a lease and may park them in DEAD_LETTER';
