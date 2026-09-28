-- 前向迁移：给审计结果约束补上第三个取值 SUBMISSION_UNKNOWN。
--
-- 这段 ALTER 原本写在 V89 里，但 V89 是在 2026-09-22 09:37 **执行之后**才被补进文件的，
-- 于是库里留下了 V79 建的两值约束，而代码早已按三值写。
-- 后果是静默审计丢失：WhatsAppCallbackConfigService 的 4 处 auditSafely(...)
-- （第 88 / 106 / 135 / 150 行）会传 "SUBMISSION_UNKNOWN"，INSERT 撞 CHECK 抛异常，
-- 异常被 auditSafely 的 catch 吞掉，只留一行 ERROR 日志 ——
-- 审计链恰好在「结果未知」这个最需要留痕的分支上断掉。
--
-- V89 已按库中 checksum 精确复原（即**不含**本段 ALTER），所以修复必须由新的迁移承担：
-- 把 V89 改回三值会让 checksum 重新漂移，而重放历史并不等于修复现状。
--
-- 幂等：先 DROP IF EXISTS 再 ADD，重复执行不会失败。
-- 取证与推导：docs/superpowers/reviews/2026-09-23-flyway-v84-v89-drift-forensics.md

ALTER TABLE whatsapp_cams_callback_audits
    DROP CONSTRAINT IF EXISTS ck_whatsapp_cams_callback_audit_result;

ALTER TABLE whatsapp_cams_callback_audits
    ADD CONSTRAINT ck_whatsapp_cams_callback_audit_result
    CHECK (result IN ('SUCCEEDED', 'FAILED', 'SUBMISSION_UNKNOWN'));
