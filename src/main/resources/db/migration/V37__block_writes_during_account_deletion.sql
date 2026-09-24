-- User status is checked again at the database write boundary. This closes the gap where a
-- request authenticated before DELETION_IN_PROGRESS was committed but writes afterward.
alter table admin_user_deletion_operations add column previous_user_status varchar(32);

create or replace function gyro_reject_write_during_account_deletion()
returns trigger
language plpgsql
as $$
declare
    owner_status varchar(32);
begin
    select status into owner_status
    from users
    where id = (to_jsonb(new) ->> tg_argv[0])::uuid;

    if owner_status in ('DELETION_IN_PROGRESS', 'DELETED') then
        raise exception 'Account is being deleted and cannot accept writes.' using errcode = '23514';
    end if;
    return new;
end;
$$;

create trigger trg_user_profiles_block_deleted_account_write before insert or update on user_profiles for each row execute function gyro_reject_write_during_account_deletion('user_id');
create trigger trg_nutrition_plans_block_deleted_account_write before insert or update on nutrition_plans for each row execute function gyro_reject_write_during_account_deletion('user_id');
create trigger trg_plan_schedules_block_deleted_account_write before insert or update on plan_schedules for each row execute function gyro_reject_write_during_account_deletion('user_id');
create trigger trg_weight_entries_block_deleted_account_write before insert or update on weight_entries for each row execute function gyro_reject_write_during_account_deletion('user_id');
create trigger trg_daily_scores_block_deleted_account_write before insert or update on daily_scores for each row execute function gyro_reject_write_during_account_deletion('user_id');
create trigger trg_diary_days_block_deleted_account_write before insert or update on diary_days for each row execute function gyro_reject_write_during_account_deletion('user_id');
create trigger trg_diary_entries_block_deleted_account_write before insert or update on diary_entries for each row execute function gyro_reject_write_during_account_deletion('user_id');
create trigger trg_meals_block_deleted_account_write before insert or update on meals for each row execute function gyro_reject_write_during_account_deletion('owner_user_id');
create trigger trg_foods_block_deleted_account_write before insert or update on foods for each row execute function gyro_reject_write_during_account_deletion('owner_user_id');
create trigger trg_food_favorites_block_deleted_account_write before insert or update on food_favorites for each row execute function gyro_reject_write_during_account_deletion('user_id');
create trigger trg_recent_foods_block_deleted_account_write before insert or update on recent_foods for each row execute function gyro_reject_write_during_account_deletion('user_id');
create trigger trg_refresh_tokens_block_deleted_account_write before insert or update on refresh_tokens for each row execute function gyro_reject_write_during_account_deletion('user_id');
create trigger trg_idempotency_keys_block_deleted_account_write before insert or update on idempotency_keys for each row when (new.user_id is not null) execute function gyro_reject_write_during_account_deletion('user_id');
