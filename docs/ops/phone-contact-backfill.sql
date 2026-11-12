-- Read-only reconciliation for phone contact backfill.
select
  count(*) as total_phone_records,
  count(*) filter (where c.created_by is not null
                         or (cr.contact_id is null and cr.created_by is not null)) as owned_records,
  count(*) filter (where cr.contact_id is not null) as linked_records,
  count(*) filter (where cr.contact_id is null
                         or c.created_by is null
                         or not exists (
                           select 1 from contact_identities ci
                           where ci.contact_id = cr.contact_id
                             and ci.channel_type = 'phone'
                             and ci.identity_scope = c.created_by::text
                             and ci.normalized_value = regexp_replace(cr.phone_point_id, '[^0-9]', '', 'g')
                             and ci.deleted_at is null
                         )) as candidate_records,
  count(*) filter (where cr.contact_id is null
                         and (cr.created_by is null
                              or not exists (select 1 from users u
                                             where u.id::text = cr.created_by
                                               and u.deleted_at is null))) as unowned_orphans
from call_records cr
left join contacts c on c.id = cr.contact_id and c.deleted_at is null
where cr.phone_point_id like 'phone:%';

select cr.id, cr.contact_id, c.created_by as contact_created_by,
  cr.created_by as call_record_created_by, cr.owner_user_id as legacy_owner_user_id,
  cr.phone_point_id, cr.version,
  case
    when c.created_by is not null then 'contact.created_by'
    when cr.contact_id is null
      and cr.created_by ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
      and exists (select 1 from users u where u.id::text = cr.created_by and u.deleted_at is null)
      then 'call_record.created_by'
    else 'unowned'
  end as ownership_source
from call_records cr
left join contacts c on c.id = cr.contact_id and c.deleted_at is null
where cr.phone_point_id like 'phone:%'
  and (cr.contact_id is null or c.created_by is null
       or not exists (
         select 1 from contact_identities ci
         where ci.contact_id = cr.contact_id
           and ci.channel_type = 'phone'
           and ci.identity_scope = c.created_by::text
           and ci.normalized_value = regexp_replace(cr.phone_point_id, '[^0-9]', '', 'g')
           and ci.deleted_at is null
       ))
order by cr.created_at, cr.id limit 1000;
