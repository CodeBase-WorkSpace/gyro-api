create table nutrition_goals
(
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users (id) on delete cascade,
    effective_date date not null,
    timezone varchar(64) not null,
    calories numeric(10,2) not null,
    protein numeric(10,3) not null,
    carbs numeric(10,3) not null,
    fat numeric(10,3) not null,
    fiber numeric(10,3),
    target_weight numeric(10,3),
    target_weight_unit varchar(8),
    target_date date,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_nutrition_goals_user_effective_date unique (user_id, effective_date),
    constraint ck_nutrition_goals_calories_positive check (calories > 0),
    constraint ck_nutrition_goals_macros_non_negative check (
        protein >= 0
        and carbs >= 0
        and fat >= 0
    ),
    constraint ck_nutrition_goals_fiber_non_negative check (fiber is null or fiber >= 0),
    constraint ck_nutrition_goals_target_weight_positive check (target_weight is null or target_weight > 0),
    constraint ck_nutrition_goals_target_weight_unit check (
        target_weight_unit is null
        or target_weight_unit in ('KG', 'LB')
    ),
    constraint ck_nutrition_goals_target_weight_pair check (
        (target_weight is null and target_weight_unit is null)
        or (target_weight is not null and target_weight_unit is not null)
    )
);

create index idx_nutrition_goals_user_effective_date_desc
    on nutrition_goals (user_id, effective_date desc);
