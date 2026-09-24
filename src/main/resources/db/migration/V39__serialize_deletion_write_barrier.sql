-- Serializes user-owned writes against the deletion barrier and closes two gaps in the
-- V37/V38 trigger:
--
-- 1. The status check now locks the owner row with FOR SHARE. The deletion barrier updates
--    the same users row (FOR UPDATE), so the two conflict: if the user-owned write locks
--    first, the barrier waits and the destructive phase later removes the written row; if
--    the barrier commits first, the write waits on the lock and then observes
--    DELETION_IN_PROGRESS and is rejected. The previous plain SELECT allowed a write whose
--    check ran just before the barrier committed to slip through after that domain had
--    already been cleared.
-- 2. UPDATEs now also validate the OLD owner, so a row belonging to a user under deletion
--    cannot escape by being reassigned to another (active) owner.
--
-- Blocked deletion writes raise the dedicated SQLSTATE 23U01 so the API can map them to a
-- stable ACCOUNT_DELETION_IN_PROGRESS error instead of a generic integrity violation.

create or replace function gyro_assert_owner_accepts_writes(owner_id uuid)
returns void
language plpgsql
as $$
declare
    owner_status varchar(32);
begin
    select status into owner_status
    from users
    where id = owner_id
    for share;

    if owner_status is null then
        raise exception 'User-owned write requires an existing owner.' using errcode = '23514';
    end if;
    if owner_status in ('DELETION_IN_PROGRESS', 'DELETED') then
        raise exception 'Account is being deleted and cannot accept writes.' using errcode = '23U01';
    end if;
end;
$$;

create or replace function gyro_reject_write_during_account_deletion()
returns trigger
language plpgsql
as $$
declare
    new_owner_id uuid;
    old_owner_id uuid;
begin
    new_owner_id := (to_jsonb(new) ->> tg_argv[0])::uuid;
    if new_owner_id is null then
        if tg_table_name in ('foods', 'idempotency_keys') then
            return new;
        end if;
        raise exception 'User-owned write requires an owner.' using errcode = '23514';
    end if;

    perform gyro_assert_owner_accepts_writes(new_owner_id);

    if tg_op = 'UPDATE' then
        old_owner_id := (to_jsonb(old) ->> tg_argv[0])::uuid;
        if old_owner_id is not null and old_owner_id is distinct from new_owner_id then
            perform gyro_assert_owner_accepts_writes(old_owner_id);
        end if;
    end if;

    return new;
end;
$$;
