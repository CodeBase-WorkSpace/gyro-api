create table food_localizations
(
    id uuid primary key default gen_random_uuid(),
    food_id uuid not null references foods(id) on delete cascade,
    locale varchar(10) not null,
    display_name varchar(500) not null,
    normalized_display_name varchar(500) not null,
    source varchar(50) not null,
    review_status varchar(50) not null default 'UNREVIEWED',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_food_localizations_locale check (locale in ('en', 'fa')),
    constraint ck_food_localizations_source check (source in ('SYSTEM', 'GYRO_CURATED', 'IMPORT')),
    constraint ck_food_localizations_review_status check (review_status in ('UNREVIEWED', 'REVIEWED', 'REJECTED')),
    constraint uq_food_localizations_food_locale unique (food_id, locale)
);

insert into food_localizations (
    food_id,
    locale,
    display_name,
    normalized_display_name,
    source,
    review_status,
    created_at,
    updated_at
)
select
    f.id,
    'en',
    f.name,
    f.normalized_name,
    'SYSTEM',
    'REVIEWED',
    now(),
    now()
from foods f
on conflict (food_id, locale) do nothing;

create index idx_food_localizations_locale on food_localizations (locale);
create index idx_food_localizations_normalized on food_localizations (normalized_display_name);
