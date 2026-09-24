create table idempotency_keys
(
    id              uuid primary key,
    scope           varchar(128) not null,
    idempotency_key varchar(255) not null,
    request_hash    varchar(128) not null,
    response_status integer      not null,
    response_body   jsonb        not null,
    created_at      timestamptz  not null,
    expires_at      timestamptz  not null,
    constraint uk_idempotency_keys_scope_key unique (scope, idempotency_key)
);

create index idx_idempotency_keys_expires_at on idempotency_keys (expires_at);
