-- Terminal outcomes prevent permanently ineligible accounts from occupying
-- every bounded reconciliation batch. Transient failures are deliberately
-- not recorded so a later sweep can retry them.
create table trial_reconciliation_outcomes
(
    user_id    uuid        primary key references users (id),
    outcome    varchar(64) not null,
    decided_at timestamptz not null default now(),

    constraint ck_trial_reconciliation_outcomes_outcome
        check (outcome in ('TRIAL_ALREADY_REDEEMED'))
);
