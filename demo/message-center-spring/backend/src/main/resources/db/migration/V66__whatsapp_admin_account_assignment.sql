ALTER TABLE whatsapp_account_assignment_audits
    DROP CONSTRAINT IF EXISTS ck_whatsapp_account_assignment_action;

UPDATE whatsapp_account_assignment_audits
SET action = 'RECLAIM'
WHERE action = 'DISABLE';

ALTER TABLE whatsapp_account_assignment_audits
    ADD CONSTRAINT ck_whatsapp_account_assignment_action
    CHECK (action IN ('ASSIGN', 'RECLAIM', 'TRANSFER'));

INSERT INTO whatsapp_account_assignment_audits (
    channel_account_id,
    previous_owner_user_id,
    next_owner_user_id,
    actor_user_id,
    action,
    reason
)
SELECT account.id,
       NULL,
       account.owner_user_id,
       account.owner_user_id,
       'ASSIGN',
       'initial_assignment_migration'
FROM channel_accounts account
WHERE account.channel_type IN ('chatapp', 'whatsapp')
  AND account.owner_user_id IS NOT NULL
  AND account.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1
      FROM whatsapp_account_assignment_audits audit
      WHERE audit.channel_account_id = account.id
  );

INSERT INTO conversation_access_grants (
    id,
    conversation_id,
    user_id,
    granted_by,
    reason
)
SELECT gen_random_uuid(),
       conversation.id,
       account.owner_user_id,
       account.owner_user_id,
       'whatsapp_assignment_history'
FROM channel_accounts account
JOIN conversations conversation ON conversation.channel_account_id = account.id
WHERE account.channel_type IN ('chatapp', 'whatsapp')
  AND account.owner_user_id IS NOT NULL
  AND account.deleted_at IS NULL
ON CONFLICT (conversation_id, user_id) WHERE revoked_at IS NULL DO NOTHING;
