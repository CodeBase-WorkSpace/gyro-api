-- Direct user-owned rows require a valid owner; shared foods and optional idempotency records
-- may be unowned. This keeps null-owner behavior explicit instead of silently permitting it.
create or replace function gyro_reject_write_during_account_deletion()
returns trigger
language plpgsql
as $$
declare
    owner_id uuid;
    owner_status varchar(32);
begin
    owner_id := (to_jsonb(new) ->> tg_argv[0])::uuid;
    if owner_id is null then
        if tg_table_name in ('foods', 'idempotency_keys') then
            return new;
        end if;
        raise exception 'User-owned write requires an owner.' using errcode = '23514';
    end if;

    select status into owner_status from users where id = owner_id;
    if owner_status is null then
        raise exception 'User-owned write requires an existing owner.' using errcode = '23514';
    end if;
    if owner_status in ('DELETION_IN_PROGRESS', 'DELETED') then
        raise exception 'Account is being deleted and cannot accept writes.' using errcode = '23514';
    end if;
    return new;
end;
$$;
