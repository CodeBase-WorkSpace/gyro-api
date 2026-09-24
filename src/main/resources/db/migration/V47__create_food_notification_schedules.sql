create table notification_schedules
(
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid        not null references users (id) on delete cascade,
    schedule_type       varchar(64) not null,
    meal_type           varchar(32),
    local_time          time        not null,
    days_of_week        smallint[]  not null,
    enabled             boolean     not null,
    state               varchar(32) not null,
    timezone_source     varchar(32) not null default 'PROFILE',
    next_evaluation_at  timestamptz,
    last_evaluation_at  timestamptz,
    claim_owner         varchar(128),
    claimed_at          timestamptz,
    claim_expires_at    timestamptz,
    claim_token         uuid,
    consented_at        timestamptz not null,
    actor_user_id       uuid        not null references users (id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    constraint ck_notification_schedule_type check (schedule_type in ('MEAL_REMINDER', 'INCOMPLETE_DAY_REMINDER')),
    constraint ck_notification_schedule_state check (state in ('ACTIVE', 'PAUSED', 'CHANNEL_UNAVAILABLE', 'INVALID_TIMEZONE')),
    constraint ck_notification_schedule_days check (cardinality(days_of_week) between 1 and 7),
    constraint ck_notification_schedule_meal check (
        (schedule_type = 'MEAL_REMINDER' and meal_type in ('BREAKFAST', 'LUNCH', 'DINNER'))
        or (schedule_type = 'INCOMPLETE_DAY_REMINDER' and meal_type is null)
    ),
    constraint ck_notification_schedule_claim check (
        (claim_owner is null and claimed_at is null and claim_expires_at is null and claim_token is null)
        or (claim_owner is not null and claimed_at is not null and claim_expires_at is not null and claim_token is not null)
    )
);

create index idx_notification_schedules_due on notification_schedules (next_evaluation_at) where enabled;
create index idx_notification_schedules_claim_expiry on notification_schedules (claim_expires_at) where claim_token is not null;
create unique index uk_notification_meal_schedule on notification_schedules (user_id, schedule_type, meal_type) where meal_type is not null;
create unique index uk_notification_incomplete_day_schedule on notification_schedules (user_id, schedule_type) where meal_type is null;

create table notification_push_subscriptions
(
    id                    uuid primary key default gen_random_uuid(),
    user_id               uuid         not null references users (id) on delete cascade,
    endpoint_ciphertext   text         not null,
    p256dh_ciphertext     text         not null,
    auth_ciphertext       text         not null,
    key_version           varchar(32)  not null,
    endpoint_fingerprint  varchar(128) not null,
    revoked_at            timestamptz,
    created_at            timestamptz  not null default now(),
    updated_at            timestamptz  not null default now(),
    constraint uk_notification_push_subscription unique (user_id, endpoint_fingerprint)
);

create index idx_notification_push_subscriptions_active on notification_push_subscriptions (user_id) where revoked_at is null;
