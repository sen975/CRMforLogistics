-- Source conversation is the stable owner for new daily summary jobs/results.
-- Existing rows remain readable through the legacy nullable identity columns.
ALTER TABLE wecom_daily_summary_jobs
    ADD COLUMN source_conversation_id uuid REFERENCES wecom_source_conversations(id),
    ADD COLUMN conversation_type varchar(20),
    ALTER COLUMN user_id DROP NOT NULL,
    ALTER COLUMN external_user_id DROP NOT NULL;

ALTER TABLE wecom_daily_summary_jobs
    DROP CONSTRAINT uq_wecom_daily_summary_job_slice,
    ADD CONSTRAINT ck_wecom_daily_summary_job_conversation_type
        CHECK (conversation_type IS NULL OR conversation_type IN ('DIRECT', 'GROUP'));

CREATE UNIQUE INDEX uq_wecom_daily_summary_jobs_source_slice
    ON wecom_daily_summary_jobs
        (installation_id, auth_corp_id, summary_day, source_conversation_id,
         slice_start, slice_end)
    WHERE source_conversation_id IS NOT NULL;

CREATE UNIQUE INDEX uq_wecom_daily_summary_jobs_legacy_slice
    ON wecom_daily_summary_jobs
        (installation_id, auth_corp_id, summary_day, user_id, external_user_id,
         slice_start, slice_end)
    WHERE source_conversation_id IS NULL;

CREATE INDEX ix_wecom_daily_summary_jobs_source_group
    ON wecom_daily_summary_jobs
        (installation_id, auth_corp_id, summary_day, source_conversation_id, slice_start)
    WHERE source_conversation_id IS NOT NULL;

ALTER TABLE wecom_daily_summaries
    ADD COLUMN source_conversation_id uuid REFERENCES wecom_source_conversations(id),
    ADD COLUMN conversation_type varchar(20),
    ALTER COLUMN user_id DROP NOT NULL,
    ALTER COLUMN external_user_id DROP NOT NULL;

ALTER TABLE wecom_daily_summaries
    DROP CONSTRAINT uq_wecom_daily_summary_group,
    ADD CONSTRAINT ck_wecom_daily_summary_conversation_type
        CHECK (conversation_type IS NULL OR conversation_type IN ('DIRECT', 'GROUP'));

CREATE UNIQUE INDEX uq_wecom_daily_summaries_source_conversation
    ON wecom_daily_summaries
        (installation_id, auth_corp_id, summary_day, source_conversation_id)
    WHERE source_conversation_id IS NOT NULL;

CREATE UNIQUE INDEX uq_wecom_daily_summaries_legacy_conversation
    ON wecom_daily_summaries
        (installation_id, auth_corp_id, summary_day, user_id, external_user_id)
    WHERE source_conversation_id IS NULL;

CREATE INDEX ix_wecom_daily_summaries_source_conversation
    ON wecom_daily_summaries
        (installation_id, auth_corp_id, summary_day, source_conversation_id)
    WHERE source_conversation_id IS NOT NULL;
