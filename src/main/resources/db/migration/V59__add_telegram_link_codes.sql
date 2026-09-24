create table notification_telegram_link_codes
(
    id                       uuid primary key default gen_random_uuid(),
    claimed_user_id          uuid references users (id) on delete cascade,
    bot_identity             varchar(64) not null,
    code_hash                varchar(64) not null,
    telegram_user_ciphertext text        not null,
    chat_id_ciphertext       text        not null,
    key_version              varchar(32) not null,
    user_fingerprint         varchar(64) not null,
    chat_fingerprint         varchar(64) not null,
    expires_at               timestamptz not null,
    consumed_at              timestamptz,
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),

    constraint uk_notification_telegram_link_code_hash unique (code_hash),
    constraint ck_notification_telegram_link_code_hash check (code_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_notification_telegram_link_code_user_fingerprint check (user_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint ck_notification_telegram_link_code_chat_fingerprint check (chat_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint ck_notification_telegram_link_code_window check (created_at < expires_at),
    constraint ck_notification_telegram_link_code_consumption check (
        (consumed_at is null and claimed_user_id is null)
        or (consumed_at is not null and consumed_at >= created_at)
    )
);

create index idx_notification_telegram_link_codes_identity_pending
    on notification_telegram_link_codes (bot_identity, user_fingerprint, expires_at desc)
    where consumed_at is null;

create index idx_notification_telegram_link_codes_expiry
    on notification_telegram_link_codes (expires_at);

create index idx_notification_telegram_link_codes_claimed_user
    on notification_telegram_link_codes (claimed_user_id)
    where claimed_user_id is not null;

create trigger trg_notification_telegram_link_codes_block_deleted_account_write
before insert or update on notification_telegram_link_codes
for each row when (new.claimed_user_id is not null)
execute function gyro_reject_write_during_account_deletion('claimed_user_id');

-- Deep-link tokens are no longer accepted. Invalidate any token created by the previous flow.
update notification_telegram_link_tokens
set consumed_at = coalesce(consumed_at, now()),
    updated_at = now()
where consumed_at is null;
