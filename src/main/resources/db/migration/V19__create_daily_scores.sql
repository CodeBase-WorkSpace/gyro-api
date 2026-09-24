create table daily_scores
(
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users (id) on delete cascade,
    local_date date not null,
    score integer not null,
    mode varchar(32) not null,
    goal_id uuid,
    goal_type varchar(32),
    formula_name varchar(80) not null,
    formula_version varchar(40) not null,
    breakdown jsonb not null,
    finalized_at timestamptz not null,
    created_at timestamptz not null default now(),
    constraint uq_daily_scores_user_date unique (user_id, local_date),
    constraint ck_daily_scores_score_range check (score between 0 and 100),
    constraint ck_daily_scores_mode check (mode in ('GOAL_ADHERENCE', 'CONSISTENCY')),
    constraint ck_daily_scores_goal_type check (
        goal_type is null
        or goal_type in ('LOSE_WEIGHT', 'MAINTAIN_WEIGHT', 'GAIN_WEIGHT')
    ),
    constraint ck_daily_scores_breakdown_object check (jsonb_typeof(breakdown) = 'object')
);

create index idx_daily_scores_user_date_desc
    on daily_scores (user_id, local_date desc);

create index idx_daily_scores_user_mode_date
    on daily_scores (user_id, mode, local_date);
