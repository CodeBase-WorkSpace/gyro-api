create table user_profiles
(
    id           uuid primary key     default gen_random_uuid(),
    user_id      uuid        not null unique references users (id) on delete cascade,

    display_name varchar(120),
    timezone     varchar(64) not null default 'Asia/Tehran',
    locale       varchar(16) not null default 'fa-IR',

    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now()
);

create index idx_user_profiles_user_id on user_profiles (user_id);