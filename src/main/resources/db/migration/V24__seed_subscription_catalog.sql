-- V24: Seed subscription plans, features, plan features, localizations, prices, and provider mappings.

-- ============================================================
-- 1. Upsert required plans
-- ============================================================
INSERT INTO subscription_plans (code, name, free, active, grace_period_days, created_at, updated_at)
VALUES
    ('FREE', 'Free', true, true, 0, now(), now()),
    ('ADVANCED', 'Advanced', false, true, 7, now(), now())
ON CONFLICT (code) DO UPDATE SET
    name = excluded.name,
    free = excluded.free,
    active = excluded.active,
    grace_period_days = excluded.grace_period_days,
    updated_at = now();

-- ============================================================
-- 2. Feature keys
-- ============================================================
INSERT INTO subscription_features (key, description, active) VALUES
    ('premium_schedules', 'Advanced goal scheduling (weekday/weekend, zigzag, custom)', true),
    ('advanced_analytics', 'Long-range nutrition, weight, goal, and maintenance analytics', true),
    ('data_export', 'CSV/JSON data export', true),
    ('future_meal_planning', 'Planned meals and meal planning calendar', true),
    ('higher_limits', 'Increased custom food and meal limits', true)
ON CONFLICT (key) DO NOTHING;

-- ============================================================
-- 3. Plan features for ADVANCED (all five enabled)
-- ============================================================
INSERT INTO plan_features (plan_id, feature_key, enabled)
SELECT p.id, f.key, true
FROM subscription_plans p, subscription_features f
WHERE p.code = 'ADVANCED'
  AND f.key IN ('premium_schedules', 'advanced_analytics', 'data_export', 'future_meal_planning', 'higher_limits')
ON CONFLICT (plan_id, feature_key) DO UPDATE SET enabled = true;

-- ============================================================
-- 4. Plan features for FREE (all five disabled)
-- ============================================================
INSERT INTO plan_features (plan_id, feature_key, enabled)
SELECT p.id, f.key, false
FROM subscription_plans p, subscription_features f
WHERE p.code = 'FREE'
  AND f.key IN ('premium_schedules', 'advanced_analytics', 'data_export', 'future_meal_planning', 'higher_limits')
ON CONFLICT (plan_id, feature_key) DO UPDATE SET enabled = false;

-- ============================================================
-- 5. Plan localizations
-- ============================================================
-- FREE plan
INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
SELECT p.id, 'fa-IR', 'رایگان', 'ردیابی روزانه کالری و درشت‌مغذی‌ها'
FROM subscription_plans p WHERE p.code = 'FREE'
ON CONFLICT (plan_id, locale) DO NOTHING;

INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
SELECT p.id, 'en-US', 'Free', 'Daily calorie and macro tracking'
FROM subscription_plans p WHERE p.code = 'FREE'
ON CONFLICT (plan_id, locale) DO NOTHING;

-- ADVANCED plan
INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
SELECT p.id, 'fa-IR', 'پیشرفته', 'برنامه‌ریزی، تحلیل و صادرات داده'
FROM subscription_plans p WHERE p.code = 'ADVANCED'
ON CONFLICT (plan_id, locale) DO NOTHING;

INSERT INTO plan_localizations (plan_id, locale, display_name, short_description)
SELECT p.id, 'en-US', 'Advanced', 'Planning, analytics, and data export'
FROM subscription_plans p WHERE p.code = 'ADVANCED'
ON CONFLICT (plan_id, locale) DO NOTHING;

-- ============================================================
-- 6. Subscription prices for ADVANCED
-- ============================================================
INSERT INTO subscription_prices (plan_id, billing_period_days, amount, currency, badge, active)
SELECT p.id, 30, 1990000.00, 'IRR', null, true
FROM subscription_plans p WHERE p.code = 'ADVANCED'
ON CONFLICT DO NOTHING;

INSERT INTO subscription_prices (plan_id, billing_period_days, amount, currency, badge, active)
SELECT p.id, 90, 5490000.00, 'IRR', 'RECOMMENDED', true
FROM subscription_plans p WHERE p.code = 'ADVANCED'
ON CONFLICT DO NOTHING;

INSERT INTO subscription_prices (plan_id, billing_period_days, amount, currency, badge, active)
SELECT p.id, 365, 19900000.00, 'IRR', 'BEST_VALUE', true
FROM subscription_plans p WHERE p.code = 'ADVANCED'
ON CONFLICT DO NOTHING;

-- ============================================================
-- 7. Provider price mappings for PAYPING PROD
-- ============================================================
INSERT INTO provider_price_mappings (subscription_price_id, provider, environment, active)
SELECT sp.id, 'PAYPING', 'PROD', true
FROM subscription_prices sp
JOIN subscription_plans p ON sp.plan_id = p.id
WHERE p.code = 'ADVANCED'
  AND sp.active = true
ON CONFLICT DO NOTHING;
