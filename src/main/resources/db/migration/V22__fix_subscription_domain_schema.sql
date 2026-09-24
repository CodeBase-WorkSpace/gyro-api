-- V22: Fix subscription domain schema issues from PR review.
-- Addresses: price versioning, FK constraints, Money precision, event idempotency.

-- ============================================================
-- 1. Fix Money precision: numeric(12) → numeric(12,2) for decimal support
-- ============================================================
-- subscription_prices.amount
alter table subscription_prices
    alter column amount type numeric(12, 2);

-- invoices.amount_due, invoices.amount_after_discount
alter table invoices
    alter column amount_due type numeric(12, 2),
    alter column amount_after_discount type numeric(12, 2);

-- payment_attempts.amount
alter table payment_attempts
    alter column amount type numeric(12, 2);

-- provider_price_mappings.provider_amount_override
alter table provider_price_mappings
    alter column provider_amount_override type numeric(12, 2);

-- ============================================================
-- 2. Fix price versioning: drop unconditional unique, add partial index
-- ============================================================
-- The old constraint blocked inserting a new active price after deactivating the old one.
-- Replace with a partial unique index that only enforces uniqueness on active rows.
alter table subscription_prices drop constraint if exists uk_plan_period_currency;

create unique index uk_plan_period_currency_active
    on subscription_prices (plan_id, billing_period_days, currency)
    where active = true;

-- ============================================================
-- 3. Add FK for locked_price_id on user_subscriptions
-- ============================================================
alter table user_subscriptions
    add constraint fk_user_subscription_locked_price
    foreign key (locked_price_id) references subscription_prices (id);

-- ============================================================
-- 4. Fail-safe: reject duplicate subscription history
-- ============================================================
-- Do NOT silently delete rows. Fail if duplicates exist so ops can clean up manually.
DO $$
BEGIN
    IF EXISTS (
        SELECT user_id, count(*)
        FROM user_subscriptions
        GROUP BY user_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'Duplicate user_subscriptions rows found. Run manual cleanup before migrating.';
    END IF;
END $$;

-- Now safely add the unique constraint
alter table user_subscriptions add constraint uk_user_subscription unique (user_id);

-- ============================================================
-- 5. Add natural key unique constraint on subscription_events
-- ============================================================
-- Enforce idempotency through the documented natural key:
-- source_type + source_id + transition_type
-- Keep idempotency_key as a secondary enforcement point.
alter table subscription_events
    add constraint uk_subscription_event_natural_key
    unique (source_type, source_id, transition_type);
