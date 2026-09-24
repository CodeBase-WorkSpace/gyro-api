create table admin_user_deletion_operations
(
    id uuid primary key default gen_random_uuid(),
    target_user_id uuid not null references users (id),
    acting_admin_id uuid not null references users (id),
    status varchar(30) not null default 'PREVIEWED',
    reason varchar(500),
    preview_token_hash varchar(128) not null,
    preview_expires_at timestamptz not null,
    domain_counts jsonb not null default '{}',
    error_message text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    completed_at timestamptz,
    constraint ck_admin_user_deletion_operations_status
        check (status in ('PREVIEWED', 'RUNNING', 'COMPLETED', 'FAILED'))
);

create index idx_admin_user_deletion_operations_target on admin_user_deletion_operations (target_user_id, created_at desc);
create unique index uq_admin_user_deletion_operations_running
    on admin_user_deletion_operations (target_user_id) where status = 'RUNNING';
