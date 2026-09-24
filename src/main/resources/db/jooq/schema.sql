-- Code generation schema projection for jOOQ.
-- Flyway migrations remain the runtime schema source of truth; keep this file
-- limited to query-heavy tables and portable DDL that jOOQ can parse.

create table foods
(
    id uuid not null,
    public_id varchar(80) not null,
    owner_user_id uuid,
    type varchar(50) not null,
    source varchar(50) not null,
    source_food_id varchar(100),
    category_id uuid,
    name varchar(500) not null,
    normalized_name varchar(500) not null,
    data_quality varchar(50) not null,
    curation_status varchar(50) not null,
    is_searchable boolean not null,
    brand_name varchar(255),
    normalized_brand_name varchar(255),
    lock_version integer not null,
    archived_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table food_categories
(
    id uuid not null,
    source varchar(50) not null,
    source_category_id varchar(100),
    name varchar(255) not null,
    normalized_name varchar(255) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table food_aliases
(
    id uuid not null,
    food_id uuid not null,
    locale varchar(10) not null,
    alias varchar(500) not null,
    normalized_alias varchar(500) not null,
    source varchar(50) not null,
    review_status varchar(50) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table food_nutrition_facts
(
    id uuid not null,
    food_id uuid not null,
    base_quantity numeric(12,4) not null,
    base_unit_id uuid not null,
    calories numeric(10,2) not null,
    protein numeric(10,2) not null,
    carbs numeric(10,2) not null,
    fat numeric(10,2) not null,
    fiber numeric(10,2) not null,
    sugar numeric(10,2) not null,
    sodium numeric(10,2) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table serving_units
(
    id uuid not null,
    code varchar(50) not null,
    unit_type varchar(50) not null,
    gram_multiplier numeric(12,6),
    milliliter_multiplier numeric(12,6),
    is_active boolean not null,
    sort_order integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table serving_unit_aliases
(
    id uuid not null,
    serving_unit_id uuid not null,
    locale varchar(10) not null,
    alias varchar(100) not null,
    normalized_alias varchar(100) not null,
    is_primary boolean not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

create table serving_unit_localizations
(
    id uuid not null,
    serving_unit_id uuid not null,
    locale varchar(10) not null,
    display_name varchar(100) not null,
    normalized_display_name varchar(100) not null,
    source varchar(50) not null,
    review_status varchar(50) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table food_localizations
(
    id uuid not null,
    food_id uuid not null,
    locale varchar(10) not null,
    display_name varchar(500) not null,
    normalized_display_name varchar(500) not null,
    source varchar(50) not null,
    review_status varchar(50) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table food_favorites
(
    id uuid not null,
    user_id uuid not null,
    food_id uuid not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

create table recent_foods
(
    id uuid not null,
    user_id uuid not null,
    food_id uuid not null,
    last_used_at timestamp with time zone not null,
    use_count integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table meals
(
    id uuid not null,
    owner_user_id uuid not null,
    name varchar(255) not null,
    normalized_name varchar(255) not null,
    total_batch_weight numeric(12, 4),
    serving_weight     numeric(12, 4),
    archived_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table meal_items
(
    id uuid not null,
    meal_id uuid not null,
    food_id uuid not null,
    quantity numeric(12,4) not null,
    serving_unit_id uuid not null,
    sort_order integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table diary_days
(
    id uuid not null,
    user_id uuid not null,
    diary_date date not null,
    timezone varchar(64) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table diary_entries
(
    id uuid not null,
    diary_day_id uuid not null,
    user_id uuid not null,
    diary_date date not null,
    meal_type varchar(32) not null,
    source_type varchar(32) not null,
    source_food_id uuid,
    source_meal_id uuid,
    source_metadata varchar(1000) not null,
    display_name_snapshot varchar(500) not null,
    serving_quantity_snapshot numeric(12,4) not null,
    serving_unit_id uuid,
    serving_unit_code_snapshot varchar(50) not null,
    serving_unit_name_snapshot varchar(100) not null,
    calories_snapshot numeric(10,2) not null,
    protein_snapshot numeric(10,2) not null,
    carbs_snapshot numeric(10,2) not null,
    fat_snapshot numeric(10,2) not null,
    fiber_snapshot numeric(10,2) not null,
    sugar_snapshot numeric(10,2) not null,
    sodium_snapshot numeric(10,2) not null,
    sort_order integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table nutrition_plans
(
    id uuid not null,
    user_id uuid not null,
    start_date date not null,
    timezone varchar(64) not null,
    calories numeric(10,2) not null,
    protein numeric(10,3) not null,
    carbs numeric(10,3) not null,
    fat numeric(10,3) not null,
    fiber numeric(10,3),
    target_weight numeric(10,3),
    target_weight_unit varchar(8),
    target_date date,
    calculator_formula varchar(80),
    calculator_formula_version varchar(40),
    calculator_sex varchar(16),
    calculator_birth_date date,
    calculator_height_cm numeric(6,2),
    calculator_current_weight_kg numeric(6,3),
    calculator_target_weight_kg numeric(6,3),
    calculator_daily_movement_level varchar(32),
    calculator_workout_frequency varchar(32),
    calculator_goal_type varchar(32),
    maintenance_calories numeric(10,2),
    target_calories numeric(10,2),
    activity_factor numeric(6,3),
    daily_energy_delta numeric(10,2),
    daily_energy_delta_source varchar(40),
    calculator_maintenance_source varchar(24),
    formula_maintenance_calories numeric(10,2),
    calculator_observation_basis varchar(10000),
    weekly_weight_change_kg numeric(8,3),
    estimated_weeks_min integer,
    estimated_weeks_max integer,
    estimated_target_date date,
    recommended_protein numeric(10,3),
    recommended_carbs numeric(10,3),
    recommended_fat numeric(10,3),
    recommended_fiber numeric(10,3),
    safety_warning_codes text[],
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table plan_schedules
(
    id uuid not null,
    user_id uuid not null,
    nutrition_plan_id uuid not null,
    schedule_type varchar(32) not null,
    active_from date not null,
    active_to date,
    weekly_calorie_budget numeric(12,2),
    weekday_targets varchar(4000) not null,
    date_overrides varchar(4000) not null,
    macro_adjustment_mode varchar(48) not null,
    diet_mode text,
    formula_name varchar(80),
    formula_version varchar(40),
    schedule_snapshot varchar(4000) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table plan_target_regime_boundaries
(
    id uuid not null,
    user_id uuid not null,
    nutrition_plan_id uuid not null,
    effective_from date not null,
    reason varchar(32) not null,
    created_at timestamp with time zone not null,
    primary key (id),
    unique (nutrition_plan_id, effective_from, reason)
);

create table weight_entries
(
    id uuid not null,
    user_id uuid not null,
    recorded_date date not null,
    recorded_at timestamp with time zone not null,
    weight_kg numeric(10,3) not null,
    display_weight numeric(10,3) not null,
    display_unit varchar(8) not null,
    source varchar(32) not null,
    notes varchar(2000),
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table daily_scores
(
    id uuid not null,
    user_id uuid not null,
    local_date date not null,
    score integer not null,
    mode varchar(32) not null,
    goal_id uuid,
    goal_type varchar(32),
    formula_name varchar(80) not null,
    formula_version varchar(40) not null,
    breakdown jsonb not null,
    finalized_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

create table coach_insight_impressions
(
    user_id uuid not null,
    kind varchar(80) not null,
    shown_on date not null,
    primary key (user_id, kind, shown_on)
);

create table food_search_terms
(
    id uuid not null,
    food_id uuid not null,
    locale varchar(10) not null,
    term varchar(500) not null,
    normalized_term varchar(500) not null,
    term_kind varchar(50) not null,
    weight numeric(8,3) not null,
    search_vector varchar(500) not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

create table food_serving_portions
(
    id uuid not null,
    food_id uuid not null,
    serving_unit_id uuid,
    amount numeric(12,4) not null,
    gram_weight numeric(12,4),
    raw_unit_name varchar(255),
    modifier varchar(255),
    portion_description varchar(500),
    source_portion_id varchar(100),
    sort_order integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table notification_templates
(
    id uuid not null,
    template_key varchar(128) not null,
    version integer not null,
    channel varchar(32) not null,
    locale varchar(32) not null,
    subject text,
    plain_body text not null,
    html_body text,
    required_variables jsonb not null,
    content_hash varchar(64) not null,
    activated_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    primary key (id)
);

create table notification_intents
(
    id uuid not null,
    user_id uuid not null,
    notification_type varchar(96) not null,
    category varchar(48) not null,
    risk varchar(16) not null,
    route_strategy varchar(48) not null,
    occurred_at timestamp with time zone not null,
    scheduled_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    idempotency_key varchar(192) not null,
    source_type varchar(64) not null,
    source_reference varchar(192) not null,
    request_id varchar(128) not null,
    template_data jsonb,
    status varchar(32) not null,
    reason varchar(48),
    terminal_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table notification_deliveries
(
    id uuid not null,
    intent_id uuid not null,
    channel varchar(32) not null,
    endpoint_reference varchar(192) not null,
    provider_request_id varchar(128) not null,
    adapter_key varchar(64) not null,
    template_key varchar(128) not null,
    template_version integer not null,
    template_locale varchar(32) not null,
    rendered_subject text,
    rendered_plain_body text,
    rendered_html_body text,
    status varchar(32) not null,
    reason varchar(48),
    due_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    attempt_count integer not null,
    next_attempt_at timestamp with time zone,
    claim_owner varchar(128),
    claimed_at timestamp with time zone,
    claim_expires_at timestamp with time zone,
    claim_token uuid,
    provider_reference_digest varchar(64),
    content_purge_at timestamp with time zone,
    content_purged_at timestamp with time zone,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);

create table notification_attempts
(
    id uuid not null,
    delivery_id uuid not null,
    attempt_number integer not null,
    started_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    duration_ms bigint,
    outcome varchar(32),
    classification varchar(48),
    estimated_sms_cost numeric(18, 4),
    provider_reference_digest varchar(64),
    created_at timestamp with time zone not null,
    primary key (id)
);

create table notification_sms_daily_quotas
(
    quota_date date not null,
    quota_key varchar(64) not null,
    used_count integer not null,
    updated_at timestamp with time zone not null,
    primary key (quota_date, quota_key)
);

create table outbox_event_consumptions
(
    id            bigint generated by default as identity,
    event_id      bigint                   not null,
    consumer_name varchar(64)              not null,
    processed_at  timestamp with time zone not null default current_timestamp,
    primary key (id),
    unique (event_id, consumer_name)
);

create table payment_attempts
(
    id         uuid                     not null,
    status     varchar(24)              not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    primary key (id)
);
