-- Repair historical ChatApp identities only when their conversation ownership
-- identifies one and only one channel account. Ambiguous rows remain visible
-- for manual review instead of being assigned to a possibly wrong sender.
WITH unique_chatapp_accounts AS (
    SELECT ci.id AS contact_identity_id,
           (array_agg(c.channel_account_id))[1] AS channel_account_id
    FROM contact_identities ci
    JOIN conversations c ON c.contact_identity_id = ci.id
    WHERE ci.channel_type = 'chatapp'
    GROUP BY ci.id
    HAVING COUNT(DISTINCT c.channel_account_id) = 1
)
UPDATE contact_identities ci
SET identity_scope = u.channel_account_id::text,
    updated_at = now()
FROM unique_chatapp_accounts u
WHERE ci.id = u.contact_identity_id
  AND ci.identity_scope = 'phone'
  AND NOT EXISTS (
      SELECT 1
      FROM contact_identities existing
      WHERE existing.id <> ci.id
        AND existing.deleted_at IS NULL
        AND existing.channel_type = 'chatapp'
        AND existing.identity_scope = u.channel_account_id::text
        AND existing.normalized_value = ci.normalized_value
  );

-- Existing ambiguous rows are deliberately not rewritten. NOT VALID keeps the
-- migration deployable while enforcing the contract for every future insert
-- and update (and for any repaired row that is subsequently changed).
ALTER TABLE contact_identities
    DROP CONSTRAINT IF EXISTS ck_contact_identities_chatapp_scope_uuid;

ALTER TABLE contact_identities
    ADD CONSTRAINT ck_contact_identities_chatapp_scope_uuid
    CHECK (
        channel_type <> 'chatapp'
        OR identity_scope ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ) NOT VALID;
