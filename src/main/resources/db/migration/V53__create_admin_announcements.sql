-- Binds an announcement id to immutable content and targets so a retried fan-out can never
-- deliver different versions of the same logical announcement.
create table admin_announcements (
    id                      uuid primary key,
    title                   varchar(80)  not null,
    body                    varchar(500) not null,
    content_hash            varchar(64)  not null,
    target_web_push         boolean      not null,
    target_telegram_channel boolean      not null,
    -- Intentionally no FK to users: announcement audit records must survive operator account deletion.
    created_by              uuid         not null,
    created_at              timestamptz  not null default now(),
    updated_at              timestamptz  not null default now()
);
