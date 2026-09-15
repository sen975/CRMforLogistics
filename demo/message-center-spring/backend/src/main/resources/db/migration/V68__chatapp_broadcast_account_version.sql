alter table chatapp_broadcasts
    add column channel_account_version bigint;

update chatapp_broadcasts b
set channel_account_version = coalesce(ca.version, 0)
from channel_accounts ca
where ca.id = b.channel_account_id
  and b.channel_account_version is null;

update chatapp_broadcasts
set channel_account_version = 0
where channel_account_version is null;

alter table chatapp_broadcasts
    alter column channel_account_version set not null;
