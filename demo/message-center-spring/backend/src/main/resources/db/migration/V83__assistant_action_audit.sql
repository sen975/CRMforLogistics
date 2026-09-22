-- 助手「决策与执行流水」表（追加写）。
--
-- 动机：这张表要回答一个具体问题——「AI 到底做了什么」。模型输出、策略判定、执行结果、错误码
-- 分属四段互不重叠的代码，只看日志无法把它们拼回一轮对话；只看待办表也看不出「是谁触发的、
-- 模型当时想调什么、被策略拦下了还是被执行了」。因此这里按轮记录，字段口径参照既有
-- V29__ai_topic_generation_attempt_audit.sql。
--
-- 写入策略：追加写、不更新、不删行。首期不加清理任务（与 wecom_contact_events 首期口径一致），
-- 后续接既有 WeComAuditRetentionService 一类机制时再统一，不单独再造第二套保留策略。
--
-- 为什么同时存 arguments 与 arguments_digest：arguments 是原文（排障与复盘要看模型到底给了什么），
-- digest 是规范化 JSON 的 sha256（用来在不解析 JSON 的前提下比对「两次是不是同一个动作」）。
-- 只留 digest 会让排障瞎眼，只留原文则每次比对都要反序列化。
CREATE TABLE assistant_action_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    conversation_id uuid,
    -- 用户原话，有界。刻意保留原话而不是只留哈希：自然语言匹配的争议只能靠原话复盘。
    utterance varchar(2000),
    -- 模型给出的分支依据。ask / call / reply / invalid（结构不符或违反硬规则时由服务端判定）。
    decision varchar(16) NOT NULL,
    tool_name varchar(64),
    arguments jsonb,
    arguments_digest varchar(64),
    -- 策略判定结果。AUTO=白名单直接执行；CONFIRM=策略要求先确认（本轮落了一条待确认动作，尚未执行）；
    -- CONFIRMED=经用户确认后执行；CANCELLED=用户取消；REJECTED=被服务端拒绝（未执行）。
    policy varchar(16),
    -- 这一轮的终点。PENDING=动作已落待确认、结果未定；EXECUTED / REJECTED / FAILED /
    -- EXPIRED / CANCELLED / INVALID / ANSWERED 见名知义。
    outcome varchar(24) NOT NULL,
    error_code varchar(64),
    -- 实际使用的模型名，用于换模型后回溯「哪段时间用的是哪个模型」。
    model varchar(128),
    -- 模型调用耗时（毫秒），含重试那次。
    latency_ms integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_assistant_action_audit_decision
        CHECK (decision IN ('ask', 'call', 'reply', 'invalid')),
    CONSTRAINT ck_assistant_action_audit_policy
        CHECK (policy IS NULL OR policy IN ('AUTO', 'CONFIRM', 'CONFIRMED', 'CANCELLED', 'REJECTED')),
    CONSTRAINT ck_assistant_action_audit_outcome
        CHECK (outcome IN ('PENDING', 'EXECUTED', 'REJECTED', 'FAILED', 'EXPIRED',
                           'CANCELLED', 'INVALID', 'ANSWERED')),
    CONSTRAINT ck_assistant_action_audit_latency CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

-- 「某用户最近发生了什么」是这张表唯一的高频查询形状。
CREATE INDEX ix_assistant_action_audit_user_created
    ON assistant_action_audit (user_id, created_at DESC);

COMMENT ON TABLE assistant_action_audit IS
    'Append-only audit of assistant decisions and executions; answers "what did the assistant actually do"';
COMMENT ON COLUMN assistant_action_audit.outcome IS
    'Terminal state of the turn; PENDING means a confirmation card was issued and nothing ran yet';
COMMENT ON COLUMN assistant_action_audit.policy IS
    'How the decision was reached: AUTO (allowlist), CONFIRM (deferred to the user), CONFIRMED, CANCELLED, REJECTED';
COMMENT ON COLUMN assistant_action_audit.arguments_digest IS
    'sha256 of the canonical (key-sorted) JSON of arguments; lets two turns be compared without parsing';
