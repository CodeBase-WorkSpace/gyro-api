create table diary_days
(
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users (id) on delete cascade,
    diary_date date not null,
    timezone varchar(64) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_diary_days_user_date unique (user_id, diary_date),
    constraint uq_diary_days_id_user_date unique (id, user_id, diary_date)
);

create table diary_entries
(
    id uuid primary key default gen_random_uuid(),
    diary_day_id uuid not null references diary_days (id) on delete cascade,
    user_id uuid not null,
    diary_date date not null,
    meal_type varchar(32) not null,
    source_type varchar(32) not null,
    source_food_id uuid references foods (id),
    source_meal_id uuid references meals (id),
    source_metadata jsonb not null default '{}'::jsonb,
    display_name_snapshot varchar(500) not null,
    serving_quantity_snapshot numeric(12,4) not null,
    serving_unit_id uuid references serving_units (id),
    serving_unit_code_snapshot varchar(50) not null,
    serving_unit_name_snapshot varchar(100) not null,
    calories_snapshot numeric(10,2) not null default 0,
    protein_snapshot numeric(10,2) not null default 0,
    carbs_snapshot numeric(10,2) not null default 0,
    fat_snapshot numeric(10,2) not null default 0,
    fiber_snapshot numeric(10,2) not null default 0,
    sugar_snapshot numeric(10,2) not null default 0,
    sodium_snapshot numeric(10,2) not null default 0,
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_diary_entries_day_owner_date
        foreign key (diary_day_id, user_id, diary_date)
        references diary_days (id, user_id, diary_date)
        on delete cascade,
    constraint ck_diary_entries_meal_type check (meal_type in ('BREAKFAST', 'LUNCH', 'DINNER', 'SNACK', 'CUSTOM')),
    constraint ck_diary_entries_source_type check (source_type in ('FOOD', 'MEAL', 'MANUAL')),
    constraint ck_diary_entries_food_source check (
        (source_type = 'FOOD' and source_food_id is not null and source_meal_id is null)
        or source_type <> 'FOOD'
    ),
    constraint ck_diary_entries_meal_source check (
        (source_type = 'MEAL' and source_meal_id is not null and source_food_id is null)
        or source_type <> 'MEAL'
    ),
    constraint ck_diary_entries_manual_source check (
        (source_type = 'MANUAL' and source_food_id is null and source_meal_id is null)
        or source_type <> 'MANUAL'
    ),
    constraint ck_diary_entries_source_metadata_object check (jsonb_typeof(source_metadata) = 'object'),
    constraint ck_diary_entries_serving_quantity check (serving_quantity_snapshot > 0),
    constraint ck_diary_entries_sort_order check (sort_order >= 0),
    constraint ck_diary_entries_nutrition_non_negative check (
        calories_snapshot >= 0
        and protein_snapshot >= 0
        and carbs_snapshot >= 0
        and fat_snapshot >= 0
        and fiber_snapshot >= 0
        and sugar_snapshot >= 0
        and sodium_snapshot >= 0
    )
);

create index idx_diary_days_user_date_desc on diary_days (user_id, diary_date desc);

create index idx_diary_entries_day_sort on diary_entries (diary_day_id, sort_order);
create index idx_diary_entries_user_date_meal on diary_entries (user_id, diary_date, meal_type);
create index idx_diary_entries_user_created on diary_entries (user_id, created_at desc);
create index idx_diary_entries_source_food on diary_entries (source_food_id) where source_food_id is not null;
create index idx_diary_entries_source_meal on diary_entries (source_meal_id) where source_meal_id is not null;
