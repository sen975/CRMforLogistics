ALTER TABLE wecom_message_summary_jobs
    ADD COLUMN IF NOT EXISTS last_error_diagnostic varchar(1000);
