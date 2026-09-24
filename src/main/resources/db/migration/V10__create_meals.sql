create table meals
(
    id uuid primary key default gen_random_uuid(),
    owner_user_id uuid not null references users (id) on delete cascade,
    name varchar(255) not null,
    normalized_name varchar(255) not null,
    archived_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table meal_items
(
    id uuid primary key default gen_random_uuid(),
    meal_id uuid not null references meals (id) on delete cascade,
    food_id uuid not null references foods (id),
    quantity numeric(12,4) not null,
    serving_unit_id uuid not null references serving_units (id),
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_meal_items_quantity check (quantity > 0),
    constraint ck_meal_items_sort_order check (sort_order >= 0)
);

create index idx_meals_owner_archived_created on meals (owner_user_id, archived_at, created_at desc);
create index idx_meals_owner_normalized_name on meals (owner_user_id, normalized_name);
create index idx_meals_normalized_name_trgm on meals using gin (normalized_name gin_trgm_ops);

create index idx_meal_items_meal_sort on meal_items (meal_id, sort_order);
create index idx_meal_items_food on meal_items (food_id);
create index idx_meal_items_serving_unit on meal_items (serving_unit_id);
