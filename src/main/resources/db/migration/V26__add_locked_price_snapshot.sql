-- V26: Add locked price snapshot columns to user_subscriptions and invoices.
-- Stores the price amount and currency at subscription creation time so renewal
-- can resolve the locked price without relying solely on the FK reference.

-- ============================================================
-- 1. user_subscriptions: locked price snapshot columns
-- ============================================================
alter table user_subscriptions
    add column locked_price_amount numeric(12, 2) null,
    add column locked_price_currency varchar(3) null;

-- FK constraint: locked_price_id references subscription_prices
alter table user_subscriptions
    add constraint fk_user_subscription_locked_price_v26
    foreign key (locked_price_id) references subscription_prices (id);

-- Index for lock lookups during renewal
create index idx_user_subscriptions_locked_price_id
    on user_subscriptions (locked_price_id);

-- Money pair consistency: both null or both non-null
alter table user_subscriptions
    add constraint ck_locked_price_pair
    check (
        (locked_price_amount is null and locked_price_currency is null)
        or
        (locked_price_amount is not null and locked_price_currency is not null)
    );

-- ============================================================
-- 2. invoices: subscription_price_id for price snapshot resolution
-- ============================================================
alter table invoices
    add column subscription_price_id bigint null;

-- FK constraint: subscription_price_id references subscription_prices
alter table invoices
    add constraint fk_invoice_subscription_price
    foreign key (subscription_price_id) references subscription_prices (id);

-- Index for price lookups during lifecycle transitions
create index idx_invoices_subscription_price_id
    on invoices (subscription_price_id);
