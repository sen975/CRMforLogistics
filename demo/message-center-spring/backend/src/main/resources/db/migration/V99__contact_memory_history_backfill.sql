ALTER TABLE contact_memory_states
    ADD COLUMN history_backfill_status varchar(20) NOT NULL DEFAULT 'COMPLETE',
    ADD COLUMN history_backfill_cursor varchar(255),
    ADD COLUMN history_backfill_target_cursor varchar(255);

ALTER TABLE contact_memory_states
    ADD CONSTRAINT ck_contact_memory_history_backfill_status
        CHECK (history_backfill_status IN ('NOT_STARTED', 'IN_PROGRESS', 'COMPLETE'));

ALTER TABLE contact_memory_attempts
    ADD COLUMN outcome varchar(20);

ALTER TABLE contact_memory_attempts
    ADD CONSTRAINT ck_contact_memory_attempt_outcome
        CHECK (outcome IS NULL OR outcome IN ('MEMORY_UPDATED', 'NO_SIGNAL', 'FAILED', 'SKIPPED'));

ALTER TABLE contact_memory_trigger_events
    ADD COLUMN memory_applied_at timestamptz;

CREATE INDEX ix_contact_memory_trigger_events_memory_pending
    ON contact_memory_trigger_events (owner_user_id, contact_id, received_at, message_id)
    WHERE memory_applied_at IS NULL;

UPDATE contact_memory_states state
SET history_backfill_status = CASE
                                  WHEN EXISTS (
                                      SELECT 1
                                      FROM contact_profile_versions profile
                                      WHERE profile.id = state.current_profile_version_id
                                        AND profile.contact_id = state.contact_id
                                        AND profile.owner_user_id = state.owner_user_id
                                        AND profile.is_current = true
                                  ) THEN 'COMPLETE'
                                  ELSE 'NOT_STARTED'
                              END,
    history_backfill_cursor = NULL,
    history_backfill_target_cursor = NULL
WHERE EXISTS (
    SELECT 1
    FROM contacts contact
    WHERE contact.id = state.contact_id
      AND contact.created_by = state.owner_user_id
);

UPDATE contact_memory_trigger_events event
SET memory_applied_at = now()
WHERE event.memory_applied_at IS NULL
  AND EXISTS (
      SELECT 1
      FROM contact_memory_states state
      JOIN contact_profile_versions profile
        ON profile.id = state.current_profile_version_id
       AND profile.contact_id = state.contact_id
       AND profile.owner_user_id = state.owner_user_id
       AND profile.is_current = true
      WHERE event.contact_id = state.contact_id
        AND event.owner_user_id = state.owner_user_id
        AND state.history_backfill_status = 'COMPLETE'
        AND state.last_success_cursor IS NOT NULL
        AND EXISTS (
            SELECT 1
            FROM messages message
            WHERE message.id = event.message_id
              AND message.direction = 'inbound'
              AND (message.received_at, message.id) <= (
                  split_part(state.last_success_cursor, '|', 1)::timestamptz,
                  split_part(state.last_success_cursor, '|', 2)::uuid
              )
        )
  );
