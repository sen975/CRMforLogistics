-- 阶段 C（L2 读写轨）的地基：把「确认卡片能装什么」与「多轮轨迹怎么复原」两件事补上。
--
-- 三处改动放在同一次迁移，因为它们回答的是同一个问题：**一轮请求跑完之后，还能不能复原它**。
--   summary     varchar(500) -> text ：卡片要能装下一封邮件的正文
--   changes     jsonb（新增）        ：卡片要能显示「改成了什么」，而不只是一句话
--   turn_index  integer（新增）      ：多轮轨迹要能按轮排序
--
-- 一个容易走偏的想法是「长正文塞进 arguments 就够了」。不行：arguments 是**给执行用的**，
-- 确认那一刻要原样重新校验（见 V82 的列注释）；用户要核对的是**将要发生什么**。
-- 让人去读一段 JSON 等于把复核成本抬高到没人会做，而这张卡片存在的全部意义
-- 就是让复核足够便宜 —— 卡片读不懂，它就从安全网退化成一个走过场的按钮。

-- 1) 卡片正文
--
-- varchar(500) 的来历是「一句话摘要」，而发邮件这类动作的正文天然是长文本。
-- 继续截断的后果不是「显示不全」，是用户在一张**看不见正文**的卡片上点确认 ——
-- 恰恰是这张卡片要防的事。text 不设长度上限；应用侧仍留软上限（AssistantPendingActionService.SUMMARY_MAX）。
ALTER TABLE assistant_pending_actions
    ALTER COLUMN summary TYPE text;

COMMENT ON COLUMN assistant_pending_actions.summary IS
    'Human-readable "what will happen", rendered on the confirmation card; long-form by design (email bodies), never a dump of raw arguments';

-- 2) 结构化「变更前后」
--
-- 形状：
--   [{"field":"date","label":"日期","before":"2026-09-23","after":"2026-09-24"}]
--
-- before 允许为 null，且**必须**在拿不到证据时留 null：摘要里编一个「改前」的值比不显示更糟，
-- 用户会拿它当事实去核对。null 由前端渲染成「当前未知」，而不是渲染成空白。
-- 整列为空（可空）表示这个动作没有「改前」可言（如新建）。
ALTER TABLE assistant_pending_actions
    ADD COLUMN changes jsonb;

COMMENT ON COLUMN assistant_pending_actions.changes IS
    'Structured before/after pairs for the confirmation card: [{"field","label","before","after"}]; before is null when unverifiable, never guessed';

-- 3) 轮次
--
-- 一次 POST /api/assistant/messages 内的第几次向模型发问（从 0 起）。
-- 可空，而且**不是**「所有行最终都会有值」：确认 / 取消 / 过期发生在**之后的另一次 HTTP 请求**里，
-- 不属于任何一轮 respond 的轮次序列。给它们编一个 0 会让「这段轨迹有多长」这类查询算错 ——
-- 派生的值不如留空诚实。
ALTER TABLE assistant_action_audit
    ADD COLUMN turn_index integer;

ALTER TABLE assistant_action_audit
    ADD CONSTRAINT ck_assistant_action_audit_turn_index
        CHECK (turn_index IS NULL OR turn_index >= 0);

-- C2 的**目的**就是复原多轮轨迹，所以这条查询形状是新增的需求而不是猜测：
-- 「某一段会话里模型依次做了什么」= where conversation_id = ? order by turn_index。
-- 现有的 ix_assistant_action_audit_user_created 是 (user_id, created_at)，覆盖不了它。
-- （created_at 也不能替代 turn_index 排序：同一轮内的两行可能同毫秒，排序会不稳定。）
CREATE INDEX ix_assistant_action_audit_conversation_turn
    ON assistant_action_audit (conversation_id, turn_index);

COMMENT ON COLUMN assistant_action_audit.turn_index IS
    'Zero-based index of the model round within one /messages request; NULL for confirm/cancel/expire, which happen in a later request';
