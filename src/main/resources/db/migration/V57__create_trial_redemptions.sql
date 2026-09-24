-- Self-serve reverse-trial redemptions: one trial per account AND per
-- normalized signup identifier (lowercased email or E.164 phone). Uniqueness
-- is enforced on both the user id and a keyed HMAC-SHA-256 of the identifier,
-- so a delete-and-resignup with the same contact cannot redeem a second
-- trial. A person with a different contact identifier can still re-trial --
-- accepted residual risk, documented in docs/product/reverse-trial.md.
-- Only the keyed hash is stored, never the raw identifier. The value is
-- pseudonymous (not anonymous): treat it as sensitive, and keep the HMAC
-- pepper (TRIAL_IDENTIFIER_PEPPER) out of the database.
create table trial_redemptions
(
    id              uuid primary key,
    user_id         uuid        not null references users (id),
    identifier_hash varchar(64) not null,
    manual_grant_id uuid        not null references manual_grants (id),
    granted_at      timestamptz not null,
    expires_at      timestamptz not null,
    notice_sent_at  timestamptz,
    source          varchar(32) not null,
    created_at      timestamptz not null default now(),

    constraint ck_trial_redemptions_source
        check (source in ('SIGNUP', 'EXISTING_USER')),
    constraint uk_trial_redemptions_identifier unique (identifier_hash),
    constraint uk_trial_redemptions_user unique (user_id)
);

create index idx_trial_redemptions_expiry
    on trial_redemptions (expires_at)
    where notice_sent_at is null;
