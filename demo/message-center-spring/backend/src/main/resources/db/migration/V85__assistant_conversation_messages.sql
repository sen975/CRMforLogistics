-- 助手会话消息表（追加写）：把「助手侧说过什么」落到库里。
--
-- 动机：在它之前，助手对话只活在浏览器内存里（useAssistant 的 React state），抽屉一关就没了。
-- 结果是「AI 说过什么」无法回溯 —— 审计表回答的是「AI 做了什么」（决策与执行流水），
-- 而用户看到的是一句话，两者不是一回事。缺了这张表，用户翻不回昨天的对话，刷新后也接不上。
--
-- 与审计表的分工（刻意不合并）：
--   assistant_action_audit          → 一轮的决策、参数、策略、结果、模型、耗时（面向排障与追责）
--   assistant_conversation_messages → 用户与助手互相说了什么（面向「把对话还给用户」）
-- 两者按 (user_id, conversation_id) 可关联，但**不共享主键**：审计可能比消息多（被拒的输出
-- 不该出现在对话里），消息也可能比审计多（用户的追问可能没产生任何决策）。
--
-- 写入策略：追加写、不更新、不删行。conversation_id 缺失时**不写** —— 那会把不同会话
-- 混进同一个 NULL 分组，比不写更糟。
--
-- 为什么不存 proposal / pending_action_id：那张卡片能不能点，取决于服务端 pending 动作的
-- **当前状态**（可能已确认、已取消、已过期），而不是取决于历史里曾经出现过它。
-- 靠历史恢复一张卡片，等于诱导用户去点一个大概率已经失效的按钮。
-- 真要恢复，正确做法是查 pending 表的当前状态，不从这里推断。
CREATE TABLE assistant_conversation_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 前端生成的会话号。同一段对话共享一个值，用于把消息串起来（也正是审计表的 conversation_id）。
    conversation_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- user=用户原话；assistant=助手这一轮的回复。
    role varchar(16) NOT NULL,
    -- 仅 assistant：那一轮的终点，与 AssistantTurnResult.Kind 一一对应。
    kind varchar(24),
    -- 正文。用 text 而不是 varchar(n)：这里的长度上限没有安全含义，截断只会让回放失真。
    text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_assistant_conversation_messages_role
        CHECK (role IN ('user', 'assistant')),
    CONSTRAINT ck_assistant_conversation_messages_kind
        CHECK (kind IS NULL OR kind IN ('QUESTION', 'CONFIRMATION_REQUIRED', 'EXECUTED', 'ANSWER', 'ERROR')),
    -- user 行不该有 kind：让「谁负责填 kind」在数据库层面说清楚，而不是靠写入方自觉。
    CONSTRAINT ck_assistant_conversation_messages_kind_role
        CHECK ((role = 'assistant') = (kind IS NOT NULL))
);

-- 唯一的读取形状：「这个会话里前面说了什么」，按时间正序。
-- user_id 放在最左是归属隔离的一部分：查询始终带上它，跨用户读不到；
-- 末尾的 id 是为了 created_at 相同时（同一次事务写入）顺序仍然确定。
CREATE INDEX ix_assistant_conversation_messages_conversation
    ON assistant_conversation_messages (user_id, conversation_id, created_at, id);

COMMENT ON TABLE assistant_conversation_messages IS
    'Append-only transcript of what the user and the assistant said; lets the panel survive a reload';
COMMENT ON COLUMN assistant_conversation_messages.kind IS
    'Terminal state of that assistant turn; NULL for user rows (only the assistant has a kind)';
