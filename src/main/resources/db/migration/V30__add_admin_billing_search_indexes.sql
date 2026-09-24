-- Support the bounded admin billing substring search without sequential scans as payment volume grows.
create extension if not exists pg_trgm;

create index if not exists idx_payment_attempts_client_ref_trgm
    on payment_attempts using gin (lower(client_ref_id) gin_trgm_ops);

create index if not exists idx_payment_attempts_provider_code_trgm
    on payment_attempts using gin (lower(provider_code) gin_trgm_ops)
    where provider_code is not null;

create index if not exists idx_payment_attempts_provider_ref_trgm
    on payment_attempts using gin (lower(provider_ref_id) gin_trgm_ops)
    where provider_ref_id is not null;

create index if not exists idx_payment_attempts_provider_request_trgm
    on payment_attempts using gin (lower(provider_request_id) gin_trgm_ops)
    where provider_request_id is not null;

create index if not exists idx_users_email_trgm
    on users using gin (lower(email) gin_trgm_ops)
    where email is not null;

create index if not exists idx_users_phone_number_trgm
    on users using gin (lower(phone_number) gin_trgm_ops)
    where phone_number is not null;

create index if not exists idx_payment_attempts_status_updated
    on payment_attempts (status, updated_at desc, created_at desc);
