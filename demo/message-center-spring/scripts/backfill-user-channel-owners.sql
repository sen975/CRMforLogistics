\set ON_ERROR_STOP on

-- This file is a read-only preflight. Apply V48 through Flyway with the normal backend
-- deployment, then run verify-user-channel-owners.sql. It intentionally never guesses
-- ownership from contact names, remarks, message bodies, or WeCom data.

select 'private_accounts_with_one_message_actor' as metric,
       count(*) as total
from (
    select ca.id
    from channel_accounts ca
    join messages m on m.channel_account_id = ca.id
    where ca.owner_user_id is null
      and ca.channel_type in ('chatapp', 'email')
      and m.created_by_user_id is not null
    group by ca.id
    having count(distinct m.created_by_user_id) = 1
) candidate;

select 'private_accounts_with_multiple_message_actors' as metric,
       count(*) as total
from (
    select ca.id
    from channel_accounts ca
    join messages m on m.channel_account_id = ca.id
    where ca.owner_user_id is null
      and ca.channel_type in ('chatapp', 'email')
      and m.created_by_user_id is not null
    group by ca.id
    having count(distinct m.created_by_user_id) > 1
) ambiguous;

select 'legacy_private_contacts_shared_by_multiple_owners' as metric,
       count(*) as total
from (
    select ci.contact_id
    from contact_identities ci
    join conversations cv on cv.contact_identity_id = ci.id
    join channel_accounts ca on ca.id = cv.channel_account_id
    where ca.channel_type in ('chatapp', 'email')
      and ca.owner_user_id is not null
      and ci.deleted_at is null
    group by ci.contact_id
    having count(distinct ca.owner_user_id) > 1
) shared;
