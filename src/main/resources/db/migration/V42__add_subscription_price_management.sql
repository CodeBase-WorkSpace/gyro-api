-- Production-safe subscription price versioning and exact-price promotion targeting.

alter table subscription_prices
    add column created_by uuid references users (id),
    add column deactivated_by uuid references users (id),
    add column deactivation_reason varchar(500),
    add column internal_notes text,
    add column scheduled boolean not null default false,
    add column expected_predecessor_id bigint references subscription_prices (id),
    add column activation_conflicted_at timestamptz,
    add column activation_conflict_reason varchar(500),
    add column version bigint not null default 0;

alter table subscription_prices
    add constraint ck_subscription_price_lifecycle check (
        not (active and scheduled)
        and (activation_conflicted_at is null or scheduled)
    );

create unique index uk_subscription_price_scheduled
    on subscription_prices (plan_id, billing_period_days, currency)
    where scheduled = true;

create index idx_subscription_price_history
    on subscription_prices (plan_id, billing_period_days, active, scheduled, created_at desc);

alter table promotions
    add column applicable_subscription_price_id bigint references subscription_prices (id);

alter table promotions
    add constraint ck_promotion_target_scope check (
        applicable_subscription_price_id is null or applicable_plan_id is not null
    );

create index idx_promotions_price_target
    on promotions (applicable_subscription_price_id)
    where applicable_subscription_price_id is not null;

create index idx_user_subscriptions_locked_price_status
    on user_subscriptions (locked_price_id, status);

create index idx_invoices_price_status
    on invoices (subscription_price_id, status);
