-- Goal recalibration (suggest + confirm): weekly suggestions computed from the
-- observed weight trend vs. logged intake. At most one PENDING suggestion per
-- user (partial unique index doubles as job idempotency); accepting updates
-- the active nutrition plan's targets in place and the suggestion row is the
-- audit trail.
create table plan_recalibration_suggestions
(
    id                  uuid primary key,
    user_id             uuid          not null references users (id),
    nutrition_plan_id   uuid          not null references nutrition_plans (id) on delete cascade,
    status              varchar(32)   not null,
    suggested_calories  numeric(10,2) not null,
    suggested_protein   numeric(10,3) not null,
    suggested_carbs     numeric(10,3) not null,
    suggested_fat       numeric(10,3) not null,
    previous_calories   numeric(10,2) not null,
    previous_protein    numeric(10,3) not null,
    previous_carbs      numeric(10,3) not null,
    previous_fat        numeric(10,3) not null,
    basis               jsonb         not null,
    created_at          timestamptz   not null default now(),
    decided_at          timestamptz,
    expires_at          timestamptz   not null,

    constraint ck_plan_recalibration_status
        check (status in ('PENDING', 'ACCEPTED', 'DISMISSED', 'EXPIRED', 'SUPERSEDED'))
);

create unique index uk_plan_recalibration_pending
    on plan_recalibration_suggestions (user_id)
    where status = 'PENDING';

create index idx_plan_recalibration_user_created
    on plan_recalibration_suggestions (user_id, created_at desc);

-- New premium feature key for the recalibration surface.
INSERT INTO subscription_features (key, description, active) VALUES
    ('goal_recalibration', 'Automatic goal recalibration from weight trend (suggest and confirm)', true)
ON CONFLICT (key) DO NOTHING;

INSERT INTO plan_features (plan_id, feature_key, enabled)
SELECT p.id, 'goal_recalibration', true
FROM subscription_plans p
WHERE p.code = 'ADVANCED'
ON CONFLICT (plan_id, feature_key) DO UPDATE SET enabled = true;

INSERT INTO plan_features (plan_id, feature_key, enabled)
SELECT p.id, 'goal_recalibration', false
FROM subscription_plans p
WHERE p.code = 'FREE'
ON CONFLICT (plan_id, feature_key) DO UPDATE SET enabled = false;

-- Suggestions are directly deleted during account deletion; guard writes
-- while the owner is being removed (deletion write-barrier contract).
create trigger trg_plan_recalibration_block_deleted_account_write
before insert or update on plan_recalibration_suggestions
for each row execute function gyro_reject_write_during_account_deletion('user_id');
