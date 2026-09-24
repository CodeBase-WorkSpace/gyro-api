create table account_audit_events
(
    id             uuid primary key     default gen_random_uuid(),

    actor_user_id  uuid references users (id),
    target_user_id uuid        not null references users (id),

    event_type     varchar(80) not null,
    reason         varchar(255),

    metadata       jsonb       not null default '{}'::jsonb,

    request_id     varchar(128),
    ip_address     varchar(64),
    user_agent     varchar(512),

    created_at     timestamptz not null default now()
);

create index idx_account_audit_events_target_user_id
    on account_audit_events (target_user_id);

create index idx_account_audit_events_created_at
    on account_audit_events (created_at);
