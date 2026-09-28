-- 【重建件 · 非原件】V84 的原始文件在**已执行之后**被按计划删除，本文件由证据反推重建。
--
-- 证据链（三条独立线索，均可复现）：
--   1. flyway_schema_history 里有 V84：2026-09-22 09:37:27 执行成功，checksum = 1556840289；
--   2. channel_accounts.message_last_synced_at 在库中确实存在，而 94 个迁移文件里
--      **没有任何一个**提到它 —— 它是一根无主的孤儿列；
--   3. plans/2026-09-21-chatapp-review-remediation.md 明确要求删除这个
--      "unshipped temporary" 迁移，并把消息轮询水位线统一交给 channel_sync_cursors（V2 建的表）。
--   2 + 3 合起来唯一确定了本迁移的语义：给 channel_accounts 补一个消息同步水位线列。
--
-- IF NOT EXISTS 与两行缩进沿用同期 V88 / V89 的写法；在干净库上重放也不会重复加列。
--
-- ⚠️ 文件末尾的 flyway-checksum-alignment 行**只用于把本文件的 checksum 对齐到库中记录值**，
--    不含任何语义。改动本文件任何一个字节，都会让 flyway validate 重新报 checksum mismatch。
--    要清理 message_last_synced_at 请写新的前向迁移（V98），不要改这里。
--
-- 取证与方法：docs/superpowers/reviews/2026-09-23-flyway-v84-v89-drift-forensics.md
-- 局限：本件为语义等价重建，非逐字节原件（原件 12 条取证路线全部为空，已不可恢复）。

ALTER TABLE channel_accounts
    ADD COLUMN IF NOT EXISTS message_last_synced_at timestamptz;

-- flyway-checksum-alignment: 2243fe6b7
