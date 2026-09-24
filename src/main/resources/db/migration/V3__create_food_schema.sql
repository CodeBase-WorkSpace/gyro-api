
create extension if not exists pgcrypto;
create extension if not exists pg_trgm;

create table food_import_batches
(
    id uuid primary key default gen_random_uuid(),
    source varchar(50) not null,
    source_file_name varchar(255) not null,
    source_version varchar(100),
    file_checksum varchar(128) not null,
    status varchar(50) not null,
    imported_count integer not null default 0,
    skipped_count integer not null default 0,
    error_count integer not null default 0,
    started_at timestamptz not null default now(),
    completed_at timestamptz,
    error_message text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_food_import_batches_source_checksum unique (source, file_checksum),
    constraint ck_food_import_batches_status check (status in ('STARTED', 'COMPLETED', 'FAILED', 'SKIPPED'))
);

create table food_categories
(
    id uuid primary key default gen_random_uuid(),
    source varchar(50) not null,
    source_category_id varchar(100),
    name varchar(255) not null,
    normalized_name varchar(255) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_food_categories_source_source_id unique (source, source_category_id),
    constraint uq_food_categories_source_normalized_name unique (source, normalized_name)
);

create table food_category_aliases
(
    id uuid primary key default gen_random_uuid(),
    category_id uuid not null references food_categories(id) on delete cascade,
    locale varchar(10) not null,
    alias varchar(255) not null,
    normalized_alias varchar(255) not null,
    is_primary boolean not null default false,
    review_status varchar(50) not null default 'REVIEWED',
    created_at timestamptz not null default now(),

    constraint uq_food_category_aliases
        unique(category_id, locale, normalized_alias)
);

create table serving_units
(
    id uuid primary key default gen_random_uuid(),
    code varchar(50) not null unique,
    unit_type varchar(50) not null,
    gram_multiplier numeric(12,6),
    milliliter_multiplier numeric(12,6),
    is_active boolean not null default true,
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_serving_units_unit_type check (unit_type in ('MASS', 'VOLUME', 'COUNT', 'HOUSEHOLD'))
);

create table serving_unit_aliases
(
    id uuid primary key default gen_random_uuid(),
    serving_unit_id uuid not null references serving_units (id) on delete cascade,
    locale varchar(10) not null,
    alias varchar(100) not null,
    normalized_alias varchar(100) not null,
    is_primary boolean not null default false,
    created_at timestamptz not null default now(),
    constraint ck_serving_unit_aliases_locale check (locale in ('en', 'fa')),
    constraint uq_serving_unit_aliases_unit_locale_alias unique (serving_unit_id, locale, normalized_alias)
);

insert into serving_units (code, unit_type, gram_multiplier, milliliter_multiplier, sort_order)
values
    ('GRAM', 'MASS', 1.000000, null, 10),
    ('MILLILITER', 'VOLUME', null, 1.000000, 20),
    ('PIECE', 'COUNT', null, null, 30),
    ('SERVING', 'HOUSEHOLD', null, null, 40),
    ('TABLESPOON', 'HOUSEHOLD', null, 14.786765, 50),
    ('TEASPOON', 'HOUSEHOLD', null, 4.928922, 60),
    ('CUP', 'HOUSEHOLD', null, 240.000000, 70),
    ('OUNCE', 'MASS', 28.349523, null, 80),
    ('SLICE', 'COUNT', null, null, 90)
on conflict (code) do nothing;

insert into serving_unit_aliases (serving_unit_id, locale, alias, normalized_alias, is_primary)
select id, 'en', 'Gram', 'gram', true from serving_units where code = 'GRAM'
union all select id, 'fa', 'گرم', 'گرم', true from serving_units where code = 'GRAM'
union all select id, 'en', 'Milliliter', 'milliliter', true from serving_units where code = 'MILLILITER'
union all select id, 'en', 'ml', 'ml', false from serving_units where code = 'MILLILITER'
union all select id, 'fa', 'میلی‌لیتر', 'میلی لیتر', true from serving_units where code = 'MILLILITER'
union all select id, 'en', 'Piece', 'piece', true from serving_units where code = 'PIECE'
union all select id, 'fa', 'عدد', 'عدد', true from serving_units where code = 'PIECE'
union all select id, 'en', 'Serving', 'serving', true from serving_units where code = 'SERVING'
union all select id, 'fa', 'وعده', 'وعده', true from serving_units where code = 'SERVING'
union all select id, 'en', 'Tablespoon', 'tablespoon', true from serving_units where code = 'TABLESPOON'
union all select id, 'en', 'tbsp', 'tbsp', false from serving_units where code = 'TABLESPOON'
union all select id, 'fa', 'قاشق غذاخوری', 'قاشق غذاخوری', true from serving_units where code = 'TABLESPOON'
union all select id, 'fa', 'ق غ', 'ق غ', false from serving_units where code = 'TABLESPOON'
union all select id, 'en', 'Teaspoon', 'teaspoon', true from serving_units where code = 'TEASPOON'
union all select id, 'en', 'tsp', 'tsp', false from serving_units where code = 'TEASPOON'
union all select id, 'fa', 'قاشق چای‌خوری', 'قاشق چای خوری', true from serving_units where code = 'TEASPOON'
union all select id, 'fa', 'ق چ', 'ق چ', false from serving_units where code = 'TEASPOON'
union all select id, 'en', 'Cup', 'cup', true from serving_units where code = 'CUP'
union all select id, 'fa', 'فنجان', 'فنجان', true from serving_units where code = 'CUP'
union all select id, 'en', 'Ounce', 'ounce', true from serving_units where code = 'OUNCE'
union all select id, 'en', 'oz', 'oz', false from serving_units where code = 'OUNCE'
union all select id, 'fa', 'اونس', 'اونس', true from serving_units where code = 'OUNCE'
union all select id, 'en', 'Slice', 'slice', true from serving_units where code = 'SLICE'
union all select id, 'fa', 'برش', 'برش', true from serving_units where code = 'SLICE'
on conflict (serving_unit_id, locale, normalized_alias) do nothing;

create table foods
(
    id uuid primary key default gen_random_uuid(),
    public_id varchar(80) not null unique,
    owner_user_id uuid references users (id),
    type varchar(50) not null,
    source varchar(50) not null,
    source_food_id varchar(100),
    category_id uuid references food_categories (id),
    name varchar(500) not null,
    normalized_name varchar(500) not null,
    data_quality varchar(50) not null default 'UNREVIEWED',
    curation_status varchar(50) not null default 'UNREVIEWED',
    is_searchable boolean not null default true,
    archived_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_foods_source_source_food_id unique (source, source_food_id),
    constraint ck_foods_type check (type in ('SYSTEM', 'CUSTOM')),
    constraint ck_foods_source check (source in ('USDA_FDC', 'GYRO_CUSTOM', 'GYRO_CURATED')),
    constraint ck_foods_data_quality check (data_quality in ('FOUNDATION', 'SR_LEGACY', 'USER_SUBMITTED', 'CURATED', 'UNREVIEWED')),
    constraint ck_foods_curation_status check (curation_status in ('REVIEWED', 'UNREVIEWED', 'HIDDEN')),
    constraint ck_foods_custom_owner_required check ((type = 'CUSTOM' and owner_user_id is not null) or (type = 'SYSTEM')),
    constraint ck_foods_system_owner_forbidden check ((type = 'SYSTEM' and owner_user_id is null) or (type = 'CUSTOM')),
    constraint ck_foods_system_source_id_required check ((type = 'SYSTEM' and source_food_id is not null) or (type = 'CUSTOM'))
);

create table food_nutrition_facts
(
    id uuid primary key default gen_random_uuid(),
    food_id uuid not null references foods (id) on delete cascade,
    base_quantity numeric(12,4) not null,
    base_unit_id uuid not null references serving_units (id),
    calories numeric(10,2) not null default 0,
    protein numeric(10,2) not null default 0,
    carbs numeric(10,2) not null default 0,
    fat numeric(10,2) not null default 0,
    fiber numeric(10,2) not null default 0,
    sugar numeric(10,2) not null default 0,
    sodium numeric(10,2) not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_food_nutrition_facts_food unique (food_id),
    constraint ck_food_nutrition_facts_base_quantity check (base_quantity > 0),
    constraint ck_food_nutrition_facts_non_negative check (
        calories >= 0 and protein >= 0 and carbs >= 0 and fat >= 0 and fiber >= 0 and sugar >= 0 and sodium >= 0
    )
);

create table food_serving_portions
(
    id uuid primary key default gen_random_uuid(),
    food_id uuid not null references foods (id) on delete cascade,
    serving_unit_id uuid references serving_units (id),
    amount numeric(12,4) not null,
    gram_weight numeric(12,4),
    raw_unit_name varchar(255),
    modifier varchar(255),
    portion_description varchar(500),
    source_portion_id varchar(100),
    sort_order integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_food_serving_portions_amount check (amount > 0),
    constraint ck_food_serving_portions_gram_weight check (gram_weight is null or gram_weight > 0),
    constraint uq_food_serving_portions_source unique (food_id, source_portion_id)
);

create table food_aliases
(
    id uuid primary key default gen_random_uuid(),
    food_id uuid not null references foods (id) on delete cascade,
    locale varchar(10) not null,
    alias varchar(500) not null,
    normalized_alias varchar(500) not null,
    source varchar(50) not null,
    review_status varchar(50) not null default 'UNREVIEWED',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_food_aliases_locale check (locale in ('en', 'fa')),
    constraint ck_food_aliases_source check (source in ('USDA', 'USER', 'GYRO_CURATED', 'IMPORT')),
    constraint ck_food_aliases_review_status check (review_status in ('UNREVIEWED', 'REVIEWED', 'REJECTED')),
    constraint uq_food_aliases_food_locale_alias unique (food_id, locale, normalized_alias)
);

create table food_source_metadata
(
    id uuid primary key default gen_random_uuid(),
    food_id uuid not null references foods (id) on delete cascade,
    import_batch_id uuid references food_import_batches (id),
    source varchar(50) not null,
    source_food_id varchar(100) not null,
    payload jsonb not null,
    imported_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    constraint uq_food_source_metadata_food_source unique (food_id, source),
    constraint uq_food_source_metadata_source_id unique (source, source_food_id)
);

create table food_search_terms
(
    id uuid primary key default gen_random_uuid(),
    food_id uuid not null references foods (id) on delete cascade,
    locale varchar(10) not null,
    term varchar(500) not null,
    normalized_term varchar(500) not null,
    term_kind varchar(50) not null,
    weight numeric(8,3) not null default 1,
    search_vector tsvector not null,
    created_at timestamptz not null default now(),
    constraint ck_food_search_terms_locale check (locale in ('en', 'fa')),
    constraint ck_food_search_terms_term_kind check (term_kind in ('NAME', 'ALIAS', 'CATEGORY', 'BRAND')),
    constraint ck_food_search_terms_weight check (weight > 0),
    constraint uq_food_search_terms_food_locale_kind_term unique (food_id, locale, term_kind, normalized_term)
);

create table food_favorites
(
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users (id) on delete cascade,
    food_id uuid not null references foods (id) on delete cascade,
    created_at timestamptz not null default now(),
    constraint uq_food_favorites_user_food unique (user_id, food_id)
);

create table recent_foods
(
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users (id) on delete cascade,
    food_id uuid not null references foods (id) on delete cascade,
    last_used_at timestamptz not null default now(),
    use_count integer not null default 1,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_recent_foods_user_food unique (user_id, food_id),
    constraint ck_recent_foods_use_count check (use_count > 0)
);

create index idx_food_import_batches_status on food_import_batches (status);
create index idx_food_import_batches_source on food_import_batches (source);

create index idx_food_categories_normalized_name on food_categories (normalized_name);

create index idx_serving_units_active_sort on serving_units (is_active, sort_order);
create index idx_serving_unit_aliases_unit on serving_unit_aliases (serving_unit_id);
create index idx_serving_unit_aliases_locale_normalized on serving_unit_aliases (locale, normalized_alias);

create index idx_foods_visibility on foods (type, is_searchable, archived_at);
create index idx_foods_owner_archived on foods (owner_user_id, archived_at);
create index idx_foods_category on foods (category_id);
create index idx_foods_source on foods (source);
create index idx_foods_curation_status on foods (curation_status);
create index idx_foods_normalized_name on foods (normalized_name);

create index idx_food_nutrition_facts_food on food_nutrition_facts (food_id);

create index idx_food_serving_portions_food_sort on food_serving_portions (food_id, sort_order);
create index idx_food_serving_portions_unit on food_serving_portions (serving_unit_id);

create index idx_food_aliases_food on food_aliases (food_id);
create index idx_food_aliases_locale_normalized on food_aliases (locale, normalized_alias);
create index idx_food_aliases_normalized_trgm on food_aliases using gin (normalized_alias gin_trgm_ops);

create index idx_food_source_metadata_import_batch on food_source_metadata (import_batch_id);
create index idx_food_source_metadata_payload on food_source_metadata using gin (payload);

create index idx_food_search_terms_food on food_search_terms (food_id);
create index idx_food_search_terms_locale_term_kind on food_search_terms (locale, term_kind);
create index idx_food_search_terms_normalized_prefix on food_search_terms (normalized_term varchar_pattern_ops);
create index idx_food_search_terms_normalized_trgm on food_search_terms using gin (normalized_term gin_trgm_ops);
create index idx_food_search_terms_search_vector on food_search_terms using gin (search_vector);

create index idx_food_favorites_user_created on food_favorites (user_id, created_at desc);
create index idx_food_favorites_food on food_favorites (food_id);

create index idx_recent_foods_user_last_used on recent_foods (user_id, last_used_at desc);
create index idx_recent_foods_user_use_count on recent_foods (user_id, use_count desc);
create index idx_recent_foods_food on recent_foods (food_id);
