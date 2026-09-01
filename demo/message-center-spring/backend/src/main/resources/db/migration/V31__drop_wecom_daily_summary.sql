-- Retire the legacy daily aggregation pipeline. Message-level jobs remain in
-- wecom_message_summary_jobs and retain their request/response diagnostics.
DROP TABLE IF EXISTS wecom_daily_summaries;
DROP TABLE IF EXISTS wecom_daily_summary_jobs;
