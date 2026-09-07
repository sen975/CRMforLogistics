ALTER TABLE ai_topic_versions
    DROP CONSTRAINT IF EXISTS ck_ai_topic_versions_change;

ALTER TABLE ai_topic_versions
    ADD CONSTRAINT ck_ai_topic_versions_change
    CHECK (change_type IN ('AI_GENERATED', 'EMPLOYEE_EDITED', 'MERGED', 'MERGED_INTO', 'STORED', 'RESTORED'));
