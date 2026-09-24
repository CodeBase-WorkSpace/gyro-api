create table notification_user_settings
(
    user_id           uuid primary key references users (id) on delete cascade,
    quiet_hours_start time not null,
    quiet_hours_end   time not null,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint ck_notification_quiet_hours_distinct check (quiet_hours_start <> quiet_hours_end)
);

create table notification_preferences
(
    id             uuid primary key default gen_random_uuid(),
    user_id        uuid not null references users (id) on delete cascade,
    category       varchar(48) not null,
    enabled        boolean not null,
    consent_source varchar(32) not null,
    policy_version varchar(64) not null,
    actor_user_id  uuid not null references users (id) on delete cascade,
    consented_at   timestamptz not null,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint uk_notification_preference_user_category unique (user_id, category),
    constraint ck_notification_preference_category check (
        category in ('OPTIONAL_BILLING', 'OPTIONAL_FOOD_LOGGING')
    )
);

create index idx_notification_preferences_user on notification_preferences (user_id);

create table notification_endpoint_health
(
    id                    uuid primary key default gen_random_uuid(),
    user_id               uuid not null references users (id) on delete cascade,
    channel               varchar(32) not null,
    account_source        varchar(32) not null,
    destination_fingerprint varchar(64) not null,
    health_state          varchar(32) not null default 'HEALTHY',
    invalid_reason        varchar(48),
    last_success_at       timestamptz,
    last_failure_at       timestamptz,
    diagnostic_delete_at  timestamptz,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now(),
    constraint uk_notification_endpoint_health unique (user_id, channel, account_source, destination_fingerprint),
    constraint ck_notification_endpoint_channel check (channel in ('EMAIL', 'SMS')),
    constraint ck_notification_endpoint_source check (account_source in ('ACCOUNT_EMAIL', 'ACCOUNT_PHONE')),
    constraint ck_notification_endpoint_health_state check (health_state in ('HEALTHY', 'INVALID')),
    constraint ck_notification_endpoint_invalid_reason check (
        invalid_reason is null or invalid_reason in ('MAILBOX_INVALID', 'DOMAIN_INVALID', 'DESTINATION_POLICY_REJECTED')
    )
);

create index idx_notification_endpoint_health_user on notification_endpoint_health (user_id, channel, health_state);
