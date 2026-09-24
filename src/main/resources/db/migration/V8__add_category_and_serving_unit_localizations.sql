create table food_category_localizations
(
    id uuid primary key default gen_random_uuid(),
    category_id uuid not null references food_categories(id) on delete cascade,
    locale varchar(10) not null,
    display_name varchar(255) not null,
    normalized_display_name varchar(255) not null,
    source varchar(50) not null,
    review_status varchar(50) not null default 'UNREVIEWED',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_food_category_localizations_locale check (locale in ('en', 'fa')),
    constraint ck_food_category_localizations_source check (source in ('SYSTEM', 'GYRO_CURATED', 'IMPORT')),
    constraint ck_food_category_localizations_review_status check (review_status in ('UNREVIEWED', 'REVIEWED', 'REJECTED')),
    constraint uq_food_category_localizations_category_locale unique (category_id, locale)
);

create table serving_unit_localizations
(
    id uuid primary key default gen_random_uuid(),
    serving_unit_id uuid not null references serving_units(id) on delete cascade,
    locale varchar(10) not null,
    display_name varchar(100) not null,
    normalized_display_name varchar(100) not null,
    source varchar(50) not null,
    review_status varchar(50) not null default 'UNREVIEWED',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_serving_unit_localizations_locale check (locale in ('en', 'fa')),
    constraint ck_serving_unit_localizations_source check (source in ('SYSTEM', 'GYRO_CURATED', 'IMPORT')),
    constraint ck_serving_unit_localizations_review_status check (review_status in ('UNREVIEWED', 'REVIEWED', 'REJECTED')),
    constraint uq_serving_unit_localizations_unit_locale unique (serving_unit_id, locale)
);

insert into food_category_localizations (
    category_id,
    locale,
    display_name,
    normalized_display_name,
    source,
    review_status,
    created_at,
    updated_at
)
select
    fc.id,
    'en',
    fc.name,
    fc.normalized_name,
    'SYSTEM',
    'REVIEWED',
    now(),
    now()
from food_categories fc
on conflict (category_id, locale) do nothing;

insert into serving_unit_localizations (
    serving_unit_id,
    locale,
    display_name,
    normalized_display_name,
    source,
    review_status,
    created_at,
    updated_at
)
select
    su.id,
    'en',
    coalesce(primary_en.alias, initcap(replace(lower(su.code), '_', ' '))),
    coalesce(primary_en.normalized_alias, replace(lower(su.code), '_', ' ')),
    'SYSTEM',
    'REVIEWED',
    now(),
    now()
from serving_units su
left join serving_unit_aliases primary_en
    on primary_en.serving_unit_id = su.id
   and primary_en.locale = 'en'
   and primary_en.is_primary = true
on conflict (serving_unit_id, locale) do nothing;

create index idx_food_category_localizations_locale on food_category_localizations (locale);
create index idx_food_category_localizations_normalized on food_category_localizations (normalized_display_name);

create index idx_serving_unit_localizations_locale on serving_unit_localizations (locale);
create index idx_serving_unit_localizations_normalized on serving_unit_localizations (normalized_display_name);
