alter table messages
    add column channel_account_version bigint;

update messages m
set channel_account_version = coalesce(ca.version, 0)
from channel_accounts ca
where ca.id = m.channel_account_id
  and m.channel_account_version is null;

update messages
set channel_account_version = 0
where channel_account_version is null;

alter table messages
    alter column channel_account_version set not null;
