alter table refresh_tokens
    add column family_id uuid,
    add column rotation_grace_expires_at timestamptz;

update refresh_tokens
set family_id = id;

alter table refresh_tokens
    alter column family_id set not null;

create index idx_refresh_tokens_family_id
    on refresh_tokens (family_id);

create index idx_refresh_tokens_rotation_grace_expires_at
    on refresh_tokens (rotation_grace_expires_at)
    where rotation_grace_expires_at is not null;
