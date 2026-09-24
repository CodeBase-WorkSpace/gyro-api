-- Add the WEIGHT_REMINDER schedule type to notification_schedules. The
-- constraints must restate the V47 definitions exactly, extended with the new
-- type; WEIGHT_REMINDER carries no meal_type, so it shares the existing
-- per-user uniqueness index for null-meal schedules
-- (uk_notification_incomplete_day_schedule on (user_id, schedule_type)).
alter table notification_schedules
    drop constraint ck_notification_schedule_type;
alter table notification_schedules
    add constraint ck_notification_schedule_type
        check (schedule_type in ('MEAL_REMINDER', 'INCOMPLETE_DAY_REMINDER', 'WEIGHT_REMINDER'));

alter table notification_schedules
    drop constraint ck_notification_schedule_meal;
alter table notification_schedules
    add constraint ck_notification_schedule_meal
        check (
            (schedule_type = 'MEAL_REMINDER' and meal_type in ('BREAKFAST', 'LUNCH', 'DINNER'))
            or (schedule_type in ('INCOMPLETE_DAY_REMINDER', 'WEIGHT_REMINDER') and meal_type is null)
        );
