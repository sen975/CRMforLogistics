-- Bind legacy DIRECT source conversations only when exactly one active WeCom identity matches.
WITH candidates AS (
    SELECT sc.id AS source_conversation_id, min(ci.id::text)::uuid AS contact_identity_id
    FROM wecom_source_conversations sc
    JOIN wecom_source_conversation_participants sp
      ON sp.source_conversation_id = sc.id
     AND sp.participant_status = 'OBSERVED'
    JOIN wecom_parties p
      ON p.id = sp.party_id
     AND p.party_type = 'EXTERNAL_CONTACT'
    JOIN contact_identities ci
      ON ci.channel_type = 'wecom'
     AND ci.identity_value = p.provider_party_id
     AND ci.deleted_at IS NULL
    JOIN channel_accounts ca
      ON ca.id::text = ci.identity_scope
     AND ca.channel_type = 'wecom'
     AND ca.auth_status = 'active'
     AND ca.deleted_at IS NULL
    WHERE sc.conversation_type = 'DIRECT'
      AND sc.contact_identity_id IS NULL
    GROUP BY sc.id
    HAVING count(DISTINCT ci.id) = 1
)
UPDATE wecom_source_conversations sc
SET contact_identity_id = candidates.contact_identity_id,
    updated_at = now()
FROM candidates
WHERE sc.id = candidates.source_conversation_id
  AND sc.contact_identity_id IS NULL;
