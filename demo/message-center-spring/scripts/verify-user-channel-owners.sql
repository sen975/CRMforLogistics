\set ON_ERROR_STOP on

-- Read-only reconciliation. Do not add business text, message bodies, addresses,
-- account configuration, credentials, or attachment paths to this report.

select 'channel_accounts' as entity,
       coalesce(channel_type, 'all') as channel_type,
       count(*) as total,
       count(*) filter (where owner_user_id is not null) as owned,
       count(*) filter (where owner_user_id is null) as unowned,
       md5(coalesce(string_agg(id::text, ',' order by id), '')) as id_digest
from channel_accounts
where deleted_at is null
group by grouping sets ((channel_type), ());

select 'contacts' as entity,
       count(*) as total,
       count(*) filter (where owner_user_id is not null) as owned,
       count(*) filter (where owner_user_id is null) as unowned,
       md5(coalesce(string_agg(id::text, ',' order by id), '')) as id_digest
from contacts
where deleted_at is null
  and status <> 'merged';

select 'private_identities' as entity,
       ci.channel_type,
       count(*) as total,
       count(*) filter (where c.owner_user_id is not null) as contact_owned,
       count(*) filter (where c.owner_user_id is null) as contact_unowned,
       md5(coalesce(string_agg(ci.id::text, ',' order by ci.id), '')) as id_digest
from contact_identities ci
join contacts c on c.id = ci.contact_id
where ci.deleted_at is null
  and ci.channel_type in ('chatapp', 'email', 'phone')
group by ci.channel_type;

select 'private_conversations' as entity,
       ca.channel_type,
       count(*) as total,
       count(*) filter (where ca.owner_user_id = c.owner_user_id) as owner_aligned,
       count(*) filter (where ca.owner_user_id is null or c.owner_user_id is null) as unowned,
       count(*) filter (where ca.owner_user_id is not null and c.owner_user_id is not null
                         and ca.owner_user_id <> c.owner_user_id) as cross_owner,
       md5(coalesce(string_agg(cv.id::text, ',' order by cv.id), '')) as id_digest
from conversations cv
join channel_accounts ca on ca.id = cv.channel_account_id
join contact_identities ci on ci.id = cv.contact_identity_id
join contacts c on c.id = ci.contact_id
where ca.channel_type in ('chatapp', 'email')
group by ca.channel_type;

select 'private_messages' as entity,
       ca.channel_type,
       count(*) as total,
       count(*) filter (where ca.owner_user_id = c.owner_user_id) as owner_aligned,
       count(*) filter (where ca.owner_user_id is null or c.owner_user_id is null) as unowned,
       count(*) filter (where ca.owner_user_id is not null and c.owner_user_id is not null
                         and ca.owner_user_id <> c.owner_user_id) as cross_owner,
       md5(coalesce(string_agg(m.id::text, ',' order by m.id), '')) as id_digest
from messages m
join channel_accounts ca on ca.id = m.channel_account_id
join conversations cv on cv.id = m.conversation_id
join contact_identities ci on ci.id = cv.contact_identity_id
join contacts c on c.id = ci.contact_id
where ca.channel_type in ('chatapp', 'email')
group by ca.channel_type;

select 'call_records' as entity,
       count(*) as total,
       count(*) filter (where owner_user_id is not null) as owned,
       count(*) filter (where owner_user_id is null) as unowned,
       count(*) filter (where owner_user_id is not null and contact_id is null) as owner_without_contact,
       md5(coalesce(string_agg(id::text, ',' order by id), '')) as id_digest
from call_records;

select 'owner_local_tags' as entity,
       count(*) as total,
       count(*) filter (where owner_user_id is not null) as owned,
       count(*) filter (where owner_user_id is null) as legacy_global,
       md5(coalesce(string_agg(id::text, ',' order by id), '')) as id_digest
from contact_tags;

select 'duplicate_private_identity_scope' as entity,
       count(*) as total,
       md5(coalesce(string_agg(scope_key, ',' order by scope_key), '')) as scope_digest
from (
    select ci.channel_type || ':' || ci.identity_scope || ':' || ci.normalized_value as scope_key
    from contact_identities ci
    where ci.deleted_at is null
      and ci.channel_type in ('chatapp', 'email', 'phone')
    group by ci.channel_type, ci.identity_scope, ci.normalized_value
    having count(*) > 1
) duplicate_scope;
