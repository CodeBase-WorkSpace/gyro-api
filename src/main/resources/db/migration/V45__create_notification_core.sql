create table notification_templates
(
    id                   uuid primary key default gen_random_uuid(),
    template_key         varchar(128) not null,
    version              integer      not null,
    channel              varchar(32)  not null,
    locale               varchar(32)  not null,
    subject              text,
    plain_body           text         not null,
    html_body            text,
    required_variables   jsonb        not null default '[]'::jsonb,
    content_hash         varchar(64)  not null,
    activated_at         timestamptz  not null,
    created_at           timestamptz  not null default now(),

    constraint uk_notification_template_version unique (template_key, version, channel, locale),
    constraint ck_notification_template_version check (version > 0),
    constraint ck_notification_template_channel check (
        channel in ('EMAIL', 'SMS', 'PUSH', 'TELEGRAM', 'IRANIAN_MESSENGER', 'IN_APP')
    ),
    constraint ck_notification_template_hash check (content_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_notification_template_variables check (jsonb_typeof(required_variables) = 'array')
);

create table notification_intents
(
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid         not null references users (id),
    notification_type   varchar(96)  not null,
    category            varchar(48)  not null,
    risk                varchar(16)  not null,
    route_strategy      varchar(48)  not null,
    occurred_at         timestamptz  not null,
    scheduled_at        timestamptz  not null,
    expires_at          timestamptz  not null,
    idempotency_key     varchar(192) not null,
    source_type         varchar(64)  not null,
    source_reference    varchar(192) not null,
    request_id          varchar(128) not null,
    template_data       jsonb,
    status              varchar(32)  not null default 'PENDING',
    reason              varchar(48),
    terminal_at         timestamptz,
    created_at          timestamptz  not null default now(),
    updated_at          timestamptz  not null default now(),

    constraint uk_notification_intent_idempotency unique (source_type, idempotency_key),
    constraint ck_notification_intent_category check (
        category in ('MANDATORY_TRANSACTIONAL', 'OPTIONAL_BILLING', 'OPTIONAL_FOOD_LOGGING')
    ),
    constraint ck_notification_intent_risk check (risk in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    constraint ck_notification_intent_route check (
        route_strategy in ('EXPLICIT_CHANNELS', 'ALL_ELIGIBLE', 'PREFERRED_AVAILABLE')
    ),
    constraint ck_notification_intent_status check (
        status in ('PENDING', 'ROUTED', 'COMPLETED', 'PARTIALLY_COMPLETED', 'SUPPRESSED', 'UNDELIVERABLE', 'EXPIRED', 'FAILED')
    ),
    constraint ck_notification_intent_reason check (
        reason is null or reason in (
            'NO_VERIFIED_ENDPOINT', 'PREFERENCE_DISABLED', 'MISSED_ALLOWED_WINDOW', 'CHANNEL_DISABLED',
            'ENDPOINT_INVALID', 'RATE_LIMIT', 'BUDGET_LIMIT', 'PROVIDER_TRANSIENT',
            'PROVIDER_PERMANENT', 'RETRY_EXHAUSTED', 'PROCESSING_FAILURE'
        )
    ),
    constraint ck_notification_intent_window check (occurred_at <= scheduled_at and scheduled_at < expires_at),
    constraint ck_notification_intent_template_data check (
        template_data is null or jsonb_typeof(template_data) = 'object'
    ),
    constraint ck_notification_intent_terminal check (
        (status in ('PENDING', 'ROUTED') and terminal_at is null)
        or (status not in ('PENDING', 'ROUTED') and terminal_at is not null)
    )
);

create index idx_notification_intents_user_created on notification_intents (user_id, created_at desc);
create index idx_notification_intents_status_created on notification_intents (status, created_at);

create table notification_deliveries
(
    id                         uuid primary key default gen_random_uuid(),
    intent_id                  uuid         not null references notification_intents (id) on delete cascade,
    channel                    varchar(32)  not null,
    endpoint_reference         varchar(192) not null,
    provider_request_id        varchar(128) not null,
    adapter_key                varchar(64)  not null,
    template_key               varchar(128) not null,
    template_version           integer      not null,
    template_locale            varchar(32)  not null,
    rendered_subject           text,
    rendered_plain_body        text,
    rendered_html_body         text,
    status                     varchar(32)  not null default 'PENDING',
    reason                     varchar(48),
    due_at                     timestamptz  not null,
    expires_at                 timestamptz  not null,
    attempt_count              integer      not null default 0,
    next_attempt_at            timestamptz,
    claim_owner                varchar(128),
    claimed_at                 timestamptz,
    claim_expires_at           timestamptz,
    claim_token                uuid,
    provider_reference_digest  varchar(64),
    content_purge_at           timestamptz,
    content_purged_at          timestamptz,
    created_at                 timestamptz  not null default now(),
    updated_at                 timestamptz  not null default now(),

    constraint uk_notification_delivery_fanout unique (intent_id, channel, endpoint_reference),
    constraint uk_notification_delivery_provider_request unique (provider_request_id),
    constraint ck_notification_delivery_channel check (
        channel in ('EMAIL', 'SMS', 'PUSH', 'TELEGRAM', 'IRANIAN_MESSENGER', 'IN_APP')
    ),
    constraint ck_notification_delivery_status check (
        status in ('PENDING', 'CLAIMED', 'RETRY_SCHEDULED', 'DELIVERED', 'SUPPRESSED', 'EXPIRED', 'PERMANENT_FAILURE', 'DEAD_LETTER')
    ),
    constraint ck_notification_delivery_reason check (
        reason is null or reason in (
            'NO_VERIFIED_ENDPOINT', 'PREFERENCE_DISABLED', 'MISSED_ALLOWED_WINDOW', 'CHANNEL_DISABLED',
            'ENDPOINT_INVALID', 'RATE_LIMIT', 'BUDGET_LIMIT', 'PROVIDER_TRANSIENT',
            'PROVIDER_PERMANENT', 'RETRY_EXHAUSTED', 'PROCESSING_FAILURE'
        )
    ),
    constraint ck_notification_delivery_attempt_count check (attempt_count >= 0),
    constraint ck_notification_delivery_window check (due_at < expires_at),
    constraint ck_notification_delivery_claim check (
        (status = 'CLAIMED' and claim_owner is not null and claimed_at is not null and claim_expires_at is not null and claim_token is not null)
        or (status <> 'CLAIMED' and claim_owner is null and claimed_at is null and claim_expires_at is null and claim_token is null)
    ),
    constraint ck_notification_delivery_content check (
        (content_purged_at is null and rendered_plain_body is not null)
        or (content_purged_at is not null and rendered_subject is null and rendered_plain_body is null and rendered_html_body is null)
    )
);

create index idx_notification_deliveries_due
    on notification_deliveries (coalesce(next_attempt_at, due_at), created_at)
    where status in ('PENDING', 'RETRY_SCHEDULED');
create index idx_notification_deliveries_expired_claim
    on notification_deliveries (claim_expires_at)
    where status = 'CLAIMED';
create index idx_notification_deliveries_intent on notification_deliveries (intent_id);
create index idx_notification_deliveries_content_purge_due
    on notification_deliveries (content_purge_at)
    where content_purge_at is not null and content_purged_at is null;

create table notification_attempts
(
    id                         uuid primary key default gen_random_uuid(),
    delivery_id                uuid        not null references notification_deliveries (id) on delete cascade,
    attempt_number             integer     not null,
    started_at                 timestamptz not null,
    completed_at               timestamptz,
    duration_ms                bigint,
    outcome                    varchar(32),
    classification             varchar(48),
    estimated_sms_cost         numeric(18, 4),
    provider_reference_digest  varchar(64),
    created_at                 timestamptz not null default now(),

    constraint uk_notification_attempt_number unique (delivery_id, attempt_number),
    constraint ck_notification_attempt_number check (attempt_number > 0),
    constraint ck_notification_attempt_duration check (duration_ms is null or duration_ms >= 0),
    constraint ck_notification_attempt_outcome check (
        outcome is null or outcome in ('SUCCESS', 'TRANSIENT_FAILURE', 'PERMANENT_FAILURE', 'INVALID_ENDPOINT', 'THROTTLED', 'UNKNOWN_FAILURE', 'UNKNOWN_AFTER_SEND')
    ),
    constraint ck_notification_attempt_classification check (
        classification is null or classification in (
            'LOG_ONLY_SUCCESS', 'PROVIDER_TRANSIENT', 'PROVIDER_PERMANENT', 'ENDPOINT_INVALID',
            'RATE_LIMIT', 'UNKNOWN_FAILURE', 'UNKNOWN_AFTER_SEND', 'INTERNAL_PROCESSING_FAILURE'
        )
    ),
    constraint ck_notification_attempt_completion check (
        (completed_at is null and duration_ms is null and outcome is null and classification is null)
        or (completed_at is not null and duration_ms is not null and outcome is not null and classification is not null)
    )
);

create index idx_notification_attempts_delivery_started on notification_attempts (delivery_id, started_at desc);
