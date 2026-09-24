alter table manual_grants
    add column period_start timestamptz;

update manual_grants
set period_start = case
                       when expires_at is not null and duration_days is not null
                           then expires_at - (duration_days * interval '1 day')
                       else created_at
    end
where period_start is null;

create index idx_manual_grants_user_effective
    on manual_grants (user_id, revoked_at, period_start, expires_at) where revoked_at is null;
