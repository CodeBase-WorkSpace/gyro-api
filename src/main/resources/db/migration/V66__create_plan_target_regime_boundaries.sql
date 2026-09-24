create table plan_target_regime_boundaries
(
    id                uuid primary key,
    user_id           uuid        not null references users (id),
    nutrition_plan_id uuid        not null references nutrition_plans (id) on delete cascade,
    effective_from    date        not null,
    reason            varchar(32) not null,
    created_at        timestamptz not null default now(),

    constraint ck_plan_target_regime_boundary_reason
        check (reason in ('SCHEDULE_CHANGE')),
    constraint uk_plan_target_regime_boundary
        unique (nutrition_plan_id, effective_from, reason)
);

create index idx_plan_target_regime_boundary_plan_effective
    on plan_target_regime_boundaries (nutrition_plan_id, effective_from desc);

create trigger trg_plan_target_regime_boundary_block_deleted_account_write
before insert or update on plan_target_regime_boundaries
for each row execute function gyro_reject_write_during_account_deletion('user_id');
