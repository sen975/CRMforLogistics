-- Backfill private-channel ownership without guessing from names or content.
--
-- Only ChatApp/email account message actors, already-owned accounts, and call-record
-- actors that exactly match an existing user UUID are trusted. WeCom rows are not
-- changed by this migration. Ambiguous rows stay unowned and therefore remain outside
-- the owner-scoped business APIs until an operator resolves them explicitly.

-- An old account can be attributed only when every attributable message actor is the
-- same CRM user. Do not create a second active account for the same owner/channel.
WITH account_candidates AS (
    SELECT ca.id AS account_id,
           ca.channel_type,
           (array_agg(m.created_by_user_id))[1] AS owner_user_id
    FROM channel_accounts ca
    JOIN messages m ON m.channel_account_id = ca.id
    WHERE ca.owner_user_id IS NULL
      AND ca.channel_type IN ('chatapp', 'email')
      AND m.created_by_user_id IS NOT NULL
    GROUP BY ca.id, ca.channel_type
    HAVING count(DISTINCT m.created_by_user_id) = 1
), eligible_candidates AS (
    SELECT candidate.*
    FROM account_candidates candidate
    WHERE NOT EXISTS (
        SELECT 1
        FROM channel_accounts competing
        WHERE competing.id <> candidate.account_id
          AND competing.owner_user_id = candidate.owner_user_id
          AND competing.channel_type = candidate.channel_type
          AND competing.auth_status IN ('active', 'expired', 'failed')
          AND competing.deleted_at IS NULL
    )
      AND 1 = (
        SELECT count(*)
        FROM account_candidates sibling
        JOIN channel_accounts sibling_account ON sibling_account.id = sibling.account_id
        WHERE sibling.owner_user_id = candidate.owner_user_id
          AND sibling.channel_type = candidate.channel_type
          AND sibling_account.auth_status IN ('active', 'expired', 'failed')
          AND sibling_account.deleted_at IS NULL
    )
)
UPDATE channel_accounts account
SET owner_user_id = candidate.owner_user_id,
    updated_at = now(),
    version = version + 1
FROM eligible_candidates candidate
WHERE account.id = candidate.account_id
  AND account.owner_user_id IS NULL;

-- Build a disposable mapping of each legacy private contact to every account owner
-- that can be proven from its conversation. A legacy WeCom contact is always copied
-- before its private conversations move, so the existing WeCom path remains intact.
CREATE TEMP TABLE owner_backfill_contact_scope ON COMMIT DROP AS
SELECT DISTINCT ci.contact_id AS source_contact_id,
                ca.owner_user_id
FROM contact_identities ci
JOIN conversations cv ON cv.contact_identity_id = ci.id
JOIN channel_accounts ca ON ca.id = cv.channel_account_id
JOIN contacts c ON c.id = ci.contact_id
WHERE c.owner_user_id IS NULL
  AND c.deleted_at IS NULL
  AND ci.deleted_at IS NULL
  AND ca.owner_user_id IS NOT NULL
  AND ca.channel_type IN ('chatapp', 'email');

-- A creator is a valid fallback only for a non-WeCom contact with a private/phone
-- identity and no attributable private account. No display name, remark or address is
-- ever used as an ownership signal.
INSERT INTO owner_backfill_contact_scope (source_contact_id, owner_user_id)
SELECT c.id, c.created_by
FROM contacts c
WHERE c.owner_user_id IS NULL
  AND c.deleted_at IS NULL
  AND c.created_by IS NOT NULL
  AND EXISTS (
      SELECT 1 FROM contact_identities ci
      WHERE ci.contact_id = c.id
        AND ci.deleted_at IS NULL
        AND ci.channel_type IN ('chatapp', 'email', 'phone')
  )
  AND NOT EXISTS (
      SELECT 1 FROM contact_identities ci
      WHERE ci.contact_id = c.id
        AND ci.deleted_at IS NULL
        AND ci.channel_type = 'wecom'
  )
  AND NOT EXISTS (
      SELECT 1 FROM owner_backfill_contact_scope scope
      WHERE scope.source_contact_id = c.id
  );

CREATE TEMP TABLE owner_backfill_contact_map ON COMMIT DROP AS
WITH distinct_scope AS (
    SELECT DISTINCT source_contact_id, owner_user_id
    FROM owner_backfill_contact_scope
), annotated AS (
    SELECT scope.source_contact_id,
           scope.owner_user_id,
           count(*) OVER (PARTITION BY scope.source_contact_id) AS owner_count,
           EXISTS (
               SELECT 1 FROM contact_identities wecom_identity
               WHERE wecom_identity.contact_id = scope.source_contact_id
                 AND wecom_identity.deleted_at IS NULL
                 AND wecom_identity.channel_type = 'wecom'
           ) AS preserves_wecom_contact
    FROM distinct_scope scope
)
SELECT source_contact_id,
       owner_user_id,
       CASE WHEN owner_count = 1 AND NOT preserves_wecom_contact THEN source_contact_id
            ELSE (
                substr(md5(source_contact_id::text || ':' || owner_user_id::text), 1, 8) || '-' ||
                substr(md5(source_contact_id::text || ':' || owner_user_id::text), 9, 4) || '-' ||
                substr(md5(source_contact_id::text || ':' || owner_user_id::text), 13, 4) || '-' ||
                substr(md5(source_contact_id::text || ':' || owner_user_id::text), 17, 4) || '-' ||
                substr(md5(source_contact_id::text || ':' || owner_user_id::text), 21, 12)
            )::uuid
       END AS target_contact_id
FROM annotated;

UPDATE contacts contact
SET owner_user_id = mapping.owner_user_id,
    updated_at = now(),
    version = version + 1
FROM owner_backfill_contact_map mapping
WHERE contact.id = mapping.source_contact_id
  AND mapping.target_contact_id = mapping.source_contact_id
  AND contact.owner_user_id IS NULL;

INSERT INTO contacts (id, owner_user_id, display_name, role_title, remark, status,
                      merged_to_id, created_by, created_at, updated_at, deleted_at, version)
SELECT mapping.target_contact_id, mapping.owner_user_id, source.display_name,
       source.role_title, source.remark, source.status, NULL, source.created_by,
       source.created_at, now(), NULL, source.version
FROM owner_backfill_contact_map mapping
JOIN contacts source ON source.id = mapping.source_contact_id
WHERE mapping.target_contact_id <> mapping.source_contact_id
ON CONFLICT (id) DO NOTHING;

-- A private identity is scoped to its account. Reuse an original identity only when
-- one account/owner is involved; otherwise create a deterministic copy for each
-- account before moving its conversations.
CREATE TEMP TABLE owner_backfill_identity_map ON COMMIT DROP AS
WITH candidates AS (
    SELECT DISTINCT ci.id AS source_identity_id,
           ci.contact_id AS source_contact_id,
           cv.channel_account_id,
           mapping.owner_user_id,
           mapping.target_contact_id
    FROM contact_identities ci
    JOIN conversations cv ON cv.contact_identity_id = ci.id
    JOIN channel_accounts ca ON ca.id = cv.channel_account_id
    JOIN owner_backfill_contact_map mapping
      ON mapping.source_contact_id = ci.contact_id
     AND mapping.owner_user_id = ca.owner_user_id
    WHERE ci.deleted_at IS NULL
      AND ci.channel_type IN ('chatapp', 'email')
      AND ca.channel_type IN ('chatapp', 'email')
      AND ca.owner_user_id IS NOT NULL
), ranked AS (
    SELECT candidate.*,
           row_number() OVER (
               PARTITION BY candidate.source_identity_id
               ORDER BY candidate.channel_account_id
           ) AS account_rank
    FROM candidates candidate
)
SELECT source_identity_id,
       source_contact_id,
       channel_account_id,
       owner_user_id,
       target_contact_id,
       CASE WHEN target_contact_id = source_contact_id AND account_rank = 1
            THEN source_identity_id
            ELSE (
                substr(md5(source_identity_id::text || ':' || channel_account_id::text), 1, 8) || '-' ||
                substr(md5(source_identity_id::text || ':' || channel_account_id::text), 9, 4) || '-' ||
                substr(md5(source_identity_id::text || ':' || channel_account_id::text), 13, 4) || '-' ||
                substr(md5(source_identity_id::text || ':' || channel_account_id::text), 17, 4) || '-' ||
                substr(md5(source_identity_id::text || ':' || channel_account_id::text), 21, 12)
            )::uuid
       END AS target_identity_id
FROM ranked;

UPDATE contact_identities identity
SET identity_scope = mapping.channel_account_id::text,
    updated_at = now()
FROM owner_backfill_identity_map mapping
WHERE identity.id = mapping.source_identity_id
  AND mapping.target_identity_id = mapping.source_identity_id
  AND NOT EXISTS (
      SELECT 1 FROM contact_identities conflicting
      WHERE conflicting.id <> identity.id
        AND conflicting.deleted_at IS NULL
        AND conflicting.channel_type = identity.channel_type
        AND conflicting.identity_scope = mapping.channel_account_id::text
        AND conflicting.normalized_value = identity.normalized_value
  );

INSERT INTO contact_identities (id, contact_id, channel_type, identity_scope,
                                identity_value, normalized_value, display_name,
                                is_primary, verify_status, source, created_at,
                                updated_at, deleted_at, version)
SELECT mapping.target_identity_id, mapping.target_contact_id, source.channel_type,
       mapping.channel_account_id::text, source.identity_value, source.normalized_value,
       source.display_name, source.is_primary, source.verify_status, source.source,
       source.created_at, now(), NULL, source.version
FROM owner_backfill_identity_map mapping
JOIN contact_identities source ON source.id = mapping.source_identity_id
WHERE mapping.target_identity_id <> mapping.source_identity_id
ON CONFLICT DO NOTHING;

UPDATE conversations conversation
SET contact_identity_id = mapping.target_identity_id,
    updated_at = now(),
    version = conversation.version + 1
FROM owner_backfill_identity_map mapping
WHERE conversation.contact_identity_id = mapping.source_identity_id
  AND conversation.channel_account_id = mapping.channel_account_id
  AND EXISTS (
      SELECT 1 FROM contact_identities target
      WHERE target.id = mapping.target_identity_id
        AND target.deleted_at IS NULL
  );

-- Phone identities are owner-scoped too. They are copied only when a shared contact
-- was split; matching is by the normalized phone value, never by a nickname.
UPDATE contact_identities identity
SET identity_scope = contact.owner_user_id::text,
    updated_at = now()
FROM contacts contact
WHERE identity.contact_id = contact.id
  AND identity.channel_type = 'phone'
  AND identity.deleted_at IS NULL
  AND contact.owner_user_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM contact_identities conflicting
      WHERE conflicting.id <> identity.id
        AND conflicting.deleted_at IS NULL
        AND conflicting.channel_type = 'phone'
        AND conflicting.identity_scope = contact.owner_user_id::text
        AND conflicting.normalized_value = identity.normalized_value
  );

INSERT INTO contact_identities (id, contact_id, channel_type, identity_scope,
                                identity_value, normalized_value, display_name,
                                is_primary, verify_status, source, created_at,
                                updated_at, deleted_at, version)
SELECT (
           substr(md5(source.id::text || ':' || mapping.owner_user_id::text), 1, 8) || '-' ||
           substr(md5(source.id::text || ':' || mapping.owner_user_id::text), 9, 4) || '-' ||
           substr(md5(source.id::text || ':' || mapping.owner_user_id::text), 13, 4) || '-' ||
           substr(md5(source.id::text || ':' || mapping.owner_user_id::text), 17, 4) || '-' ||
           substr(md5(source.id::text || ':' || mapping.owner_user_id::text), 21, 12)
       )::uuid,
       mapping.target_contact_id, source.channel_type, mapping.owner_user_id::text,
       source.identity_value, source.normalized_value, source.display_name,
       source.is_primary, source.verify_status, source.source, source.created_at,
       now(), NULL, source.version
FROM owner_backfill_contact_map mapping
JOIN contact_identities source ON source.contact_id = mapping.source_contact_id
WHERE mapping.target_contact_id <> mapping.source_contact_id
  AND source.deleted_at IS NULL
  AND source.channel_type = 'phone'
ON CONFLICT DO NOTHING;

-- Do not mutate global/WeCom tags. Copy each legacy tag into every proven owner scope
-- and attach the owner-local copy to that owner's private contact.
CREATE TEMP TABLE owner_backfill_tag_scope ON COMMIT DROP AS
SELECT DISTINCT tagging.tag_id AS source_tag_id,
                contact.owner_user_id
FROM contact_taggings tagging
JOIN contacts contact ON contact.id = tagging.contact_id
WHERE contact.owner_user_id IS NOT NULL;

INSERT INTO contact_tags (id, owner_user_id, name, color, status, created_at)
SELECT (
           substr(md5(scope.source_tag_id::text || ':' || scope.owner_user_id::text), 1, 8) || '-' ||
           substr(md5(scope.source_tag_id::text || ':' || scope.owner_user_id::text), 9, 4) || '-' ||
           substr(md5(scope.source_tag_id::text || ':' || scope.owner_user_id::text), 13, 4) || '-' ||
           substr(md5(scope.source_tag_id::text || ':' || scope.owner_user_id::text), 17, 4) || '-' ||
           substr(md5(scope.source_tag_id::text || ':' || scope.owner_user_id::text), 21, 12)
       )::uuid,
       scope.owner_user_id, source.name, source.color, source.status, source.created_at
FROM owner_backfill_tag_scope scope
JOIN contact_tags source ON source.id = scope.source_tag_id
ON CONFLICT DO NOTHING;

INSERT INTO contact_taggings (contact_id, tag_id)
SELECT contact.id, owner_tag.id
FROM contact_taggings legacy_tagging
JOIN contacts contact ON contact.id = legacy_tagging.contact_id
JOIN contact_tags legacy_tag ON legacy_tag.id = legacy_tagging.tag_id
JOIN contact_tags owner_tag
  ON owner_tag.owner_user_id = contact.owner_user_id
 AND lower(owner_tag.name) = lower(legacy_tag.name)
WHERE contact.owner_user_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- The legacy call actor is text. Attribute it only when it is a UUID-shaped value that
-- exactly equals a current user id; otherwise leave the record unowned.
UPDATE call_records call_record
SET owner_user_id = actor.id,
    updated_at = now(),
    version = call_record.version + 1
FROM users actor
WHERE call_record.owner_user_id IS NULL
  AND call_record.created_by ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  AND lower(call_record.created_by) = actor.id::text;

WITH unambiguous_phone_contact AS (
    SELECT call_record.id AS call_record_id,
           (array_agg(identity.contact_id))[1] AS contact_id
    FROM call_records call_record
    JOIN contact_identities identity
      ON identity.channel_type = 'phone'
     AND identity.deleted_at IS NULL
     AND call_record.phone_point_id = 'phone:' || identity.normalized_value
    JOIN contacts contact
      ON contact.id = identity.contact_id
     AND contact.owner_user_id = call_record.owner_user_id
    WHERE call_record.owner_user_id IS NOT NULL
      AND call_record.contact_id IS NULL
    GROUP BY call_record.id
    HAVING count(DISTINCT identity.contact_id) = 1
)
UPDATE call_records call_record
SET contact_id = match.contact_id,
    updated_at = now(),
    version = call_record.version + 1
FROM unambiguous_phone_contact match
WHERE call_record.id = match.call_record_id
  AND call_record.contact_id IS NULL;
