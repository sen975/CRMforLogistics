-- 前向迁移：删除 V84 留下的孤儿列 channel_accounts.message_last_synced_at。
--
-- 这条列由 V84 建立，而 V84 随后被按计划删除（plans/2026-09-21-chatapp-review-remediation.md：
-- 把消息轮询水位线统一交给 channel_sync_cursors，即 V2 建的那张表）。
-- 迁移文件删了，列却已经落在库里，于是它成了无主字段：src/main 与 src/test 中 **0 处引用**，
-- 95 个迁移文件里也再无一处提到它。
--
-- V84 的文件已按库中 checksum 重建（只为让账本完整、validate 通过），
-- 因此不能顺手在重建件里删列 —— 那会让 checksum 重新对不上。
-- 「删掉这条列」只能由新的前向迁移承担，也就是本文件。
--
-- 幂等：DROP COLUMN IF EXISTS，重复执行不会失败。
-- 取证与推导：docs/superpowers/reviews/2026-09-23-flyway-v84-v89-drift-forensics.md

ALTER TABLE channel_accounts
    DROP COLUMN IF EXISTS message_last_synced_at;
