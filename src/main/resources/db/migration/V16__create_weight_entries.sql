create table weight_entries
(
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references users (id) on delete cascade,
    recorded_date date not null,
    recorded_at timestamptz not null default now(),
    weight_kg numeric(10,3) not null,
    display_weight numeric(10,3) not null,
    display_unit varchar(8) not null,
    source varchar(32) not null,
    notes text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_weight_entries_user_recorded_date unique (user_id, recorded_date),
    constraint ck_weight_entries_weight_kg_range check (weight_kg between 20 and 500),
    constraint ck_weight_entries_display_weight_positive check (display_weight > 0),
    constraint ck_weight_entries_display_unit check (display_unit in ('KG', 'LB')),
    constraint ck_weight_entries_source check (source in ('MANUAL', 'IMPORT')),
    constraint ck_weight_entries_notes_length check (notes is null or char_length(notes) <= 2000)
);

create index idx_weight_entries_user_recorded_date_desc
    on weight_entries (user_id, recorded_date desc);

create index idx_weight_entries_user_recorded_at_desc
    on weight_entries (user_id, recorded_at desc);
