create table goal_schedules
(
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users (id) on delete cascade,
    nutrition_goal_id uuid not null references nutrition_goals (id) on delete cascade,
    schedule_type varchar(32) not null,
    active_from date not null,
    active_to date,
    weekly_calorie_budget numeric(12,2),
    weekday_targets jsonb not null default '{}'::jsonb,
    date_overrides jsonb not null default '{}'::jsonb,
    macro_adjustment_mode varchar(48) not null,
    diet_mode varchar(64),
    formula_name varchar(80),
    formula_version varchar(40),
    schedule_snapshot jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_goal_schedules_goal unique (nutrition_goal_id),
    constraint ck_goal_schedules_type check (
        schedule_type in ('FLAT', 'WEEKDAY_WEEKEND', 'ZIGZAG', 'CUSTOM')
    ),
    constraint ck_goal_schedules_active_range check (
        active_to is null
        or active_to >= active_from
    ),
    constraint ck_goal_schedules_weekly_calorie_budget check (
        weekly_calorie_budget is null
        or weekly_calorie_budget between 800 and 140000
    ),
    constraint ck_goal_schedules_json_objects check (
        jsonb_typeof(weekday_targets) = 'object'
        and jsonb_typeof(date_overrides) = 'object'
        and jsonb_typeof(schedule_snapshot) = 'object'
    ),
    constraint ck_goal_schedules_macro_adjustment_mode check (
        macro_adjustment_mode in (
            'FIXED_GRAMS',
            'SCALE_WITH_CALORIES',
            'FIXED_PROTEIN_FLEXIBLE_CARBS_FAT'
        )
    ),
    constraint ck_goal_schedules_flat_defaults check (
        schedule_type <> 'FLAT'
        or (
            weekly_calorie_budget is null
            and weekday_targets = '{}'::jsonb
            and date_overrides = '{}'::jsonb
            and diet_mode is null
        )
    )
);

create index idx_goal_schedules_user_active_range
    on goal_schedules (user_id, active_from, active_to);

create index idx_goal_schedules_user_type
    on goal_schedules (user_id, schedule_type);

insert into goal_schedules (
    user_id,
    nutrition_goal_id,
    schedule_type,
    active_from,
    active_to,
    macro_adjustment_mode,
    formula_name,
    formula_version,
    created_at,
    updated_at
)
select
    user_id,
    id,
    'FLAT',
    effective_date,
    next_effective_date - 1,
    'FIXED_GRAMS',
    calculator_formula,
    calculator_formula_version,
    created_at,
    updated_at
from (
    select
        nutrition_goals.*,
        lead(effective_date) over (
            partition by user_id
            order by effective_date
        ) as next_effective_date
    from nutrition_goals
) ordered_goals;
