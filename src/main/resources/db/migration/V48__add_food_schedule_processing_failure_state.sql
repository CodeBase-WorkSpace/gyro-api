alter table notification_schedules
    add column processing_failure_count integer not null default 0,
    add column last_processing_failure_at timestamptz,
    add column last_processing_failure_class varchar(80);

alter table notification_schedules drop constraint ck_notification_schedule_state;
alter table notification_schedules add constraint ck_notification_schedule_state
    check (state in ('ACTIVE', 'PAUSED', 'CHANNEL_UNAVAILABLE', 'INVALID_TIMEZONE', 'PROCESSING_FAILED'));

create index notification_schedules_processing_failure_idx
    on notification_schedules (processing_failure_count, last_processing_failure_at)
    where processing_failure_count > 0;
