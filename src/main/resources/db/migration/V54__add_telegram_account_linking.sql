create table notification_telegram_link_tokens
(
    id           uuid primary key default gen_random_uuid(),
    user_id      uuid        not null references users (id) on delete cascade,
    token_hash   varchar(64) not null,
    expires_at   timestamptz not null,
    consumed_at  timestamptz,
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),

    constraint uk_notification_telegram_link_token_hash unique (token_hash),
    constraint ck_notification_telegram_link_token_hash check (token_hash ~ '^[0-9a-f]{64}$'),
    constraint ck_notification_telegram_link_token_window check (created_at < expires_at),
    constraint ck_notification_telegram_link_token_consumption check (consumed_at is null or consumed_at >= created_at)
);

create index idx_notification_telegram_link_tokens_user_pending
    on notification_telegram_link_tokens (user_id, expires_at desc)
    where consumed_at is null;

create table notification_telegram_endpoints
(
    id                       uuid primary key default gen_random_uuid(),
    user_id                  uuid        not null references users (id) on delete cascade,
    bot_identity             varchar(64) not null,
    telegram_user_ciphertext text        not null,
    chat_id_ciphertext       text        not null,
    key_version              varchar(32) not null,
    user_fingerprint         varchar(64) not null,
    chat_fingerprint         varchar(64) not null,
    state                    varchar(16) not null,
    linked_at                timestamptz not null,
    verified_at              timestamptz not null,
    last_inbound_at          timestamptz not null,
    revoked_at               timestamptz,
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),

    constraint uk_notification_telegram_endpoint_user unique (user_id, bot_identity),
    constraint uk_notification_telegram_endpoint_identity unique (bot_identity, user_fingerprint),
    constraint uk_notification_telegram_endpoint_chat unique (bot_identity, chat_fingerprint),
    constraint ck_notification_telegram_endpoint_state check (state in ('ACTIVE', 'BLOCKED', 'REVOKED')),
    constraint ck_notification_telegram_endpoint_user_fingerprint check (user_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint ck_notification_telegram_endpoint_chat_fingerprint check (chat_fingerprint ~ '^[0-9a-f]{64}$'),
    constraint ck_notification_telegram_endpoint_revocation check (
        (state = 'REVOKED' and revoked_at is not null) or (state <> 'REVOKED' and revoked_at is null)
    )
);

create index idx_notification_telegram_endpoints_active
    on notification_telegram_endpoints (user_id, bot_identity)
    where state = 'ACTIVE';

create table notification_telegram_webhook_updates
(
    bot_identity varchar(64) not null,
    update_id    bigint      not null,
    received_at  timestamptz not null,

    constraint pk_notification_telegram_webhook_updates primary key (bot_identity, update_id),
    constraint ck_notification_telegram_webhook_update_id check (update_id >= 0)
);

create index idx_notification_telegram_webhook_updates_received
    on notification_telegram_webhook_updates (received_at);

create trigger trg_notification_telegram_link_tokens_block_deleted_account_write
before insert or update on notification_telegram_link_tokens
for each row execute function gyro_reject_write_during_account_deletion('user_id');

create trigger trg_notification_telegram_endpoints_block_deleted_account_write
before insert or update on notification_telegram_endpoints
for each row execute function gyro_reject_write_during_account_deletion('user_id');
