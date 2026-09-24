-- Notification preference, endpoint, schedule, and push rows are directly deleted during
-- account deletion. Guard their write paths while the owner (or consent actor) is being removed.
create trigger trg_notification_user_settings_block_deleted_account_write
before insert or update on notification_user_settings
for each row execute function gyro_reject_write_during_account_deletion('user_id');

create trigger trg_notification_preferences_owner_block_deleted_account_write
before insert or update on notification_preferences
for each row execute function gyro_reject_write_during_account_deletion('user_id');

create trigger trg_notification_preferences_actor_block_deleted_account_write
before insert or update on notification_preferences
for each row execute function gyro_reject_write_during_account_deletion('actor_user_id');

create trigger trg_notification_endpoint_health_block_deleted_account_write
before insert or update on notification_endpoint_health
for each row execute function gyro_reject_write_during_account_deletion('user_id');

create trigger trg_notification_schedules_owner_block_deleted_account_write
before insert or update on notification_schedules
for each row execute function gyro_reject_write_during_account_deletion('user_id');

create trigger trg_notification_schedules_actor_block_deleted_account_write
before insert or update on notification_schedules
for each row execute function gyro_reject_write_during_account_deletion('actor_user_id');

create trigger trg_notification_push_subscriptions_block_deleted_account_write
before insert or update on notification_push_subscriptions
for each row execute function gyro_reject_write_during_account_deletion('user_id');
