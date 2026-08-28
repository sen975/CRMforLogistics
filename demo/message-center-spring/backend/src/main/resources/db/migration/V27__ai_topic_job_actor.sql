ALTER TABLE ai_topic_generation_jobs
    ADD COLUMN IF NOT EXISTS created_by_user_id uuid
    REFERENCES users(id) ON DELETE SET NULL;
