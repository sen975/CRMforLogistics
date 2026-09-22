-- 只读轨（L1）落地后，策略判定多了一档：READ。
--
-- 为什么扩 CHECK 而不是复用 AUTO：这两档的安全含义不同，混成一个值会让库里的多轮轨迹
-- 无法复原。
--   AUTO = 免确认，且**本轮就此结束**（只增不改的写动作）
--   READ = 免确认，且**允许在同一次请求里继续循环**（只读动作）
-- 换句话说 READ 是「循环只对只读动作开放」这条安全闸门在库里的落点：
-- 复盘一轮请求时，第一个要回答的问题就是「哪几行是只读轮、哪一行是写动作」。
--
-- 只改约束，不动已有数据：'READ' 是一个新增的合法值，历史行的取值仍然合法。
ALTER TABLE assistant_action_audit
    DROP CONSTRAINT ck_assistant_action_audit_policy;

ALTER TABLE assistant_action_audit
    ADD CONSTRAINT ck_assistant_action_audit_policy
        CHECK (policy IS NULL OR policy IN ('AUTO', 'CONFIRM', 'CONFIRMED', 'CANCELLED', 'REJECTED', 'READ'));

COMMENT ON COLUMN assistant_action_audit.policy IS
    'How the decision was reached: READ (read-only tool,免确认且可继续循环), AUTO (allowlist,免确认且终止本轮), CONFIRM (deferred to the user), CONFIRMED, CANCELLED, REJECTED';
