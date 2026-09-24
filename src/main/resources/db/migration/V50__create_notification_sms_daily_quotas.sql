-- A row is atomically incremented before each product-SMS provider call.
-- `quota_key = global` enforces the deployment-wide cap; user rows enforce the per-user cap.
create table notification_sms_daily_quotas (
    quota_date date not null,
    quota_key varchar(64) not null,
    used_count integer not null check (used_count >= 0),
    updated_at timestamp with time zone not null,
    primary key (quota_date, quota_key)
);
