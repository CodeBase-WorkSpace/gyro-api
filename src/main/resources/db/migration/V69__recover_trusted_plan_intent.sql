ALTER TABLE nutrition_plans
    ADD COLUMN daily_energy_delta_source VARCHAR(40),
    ADD COLUMN calculator_maintenance_source VARCHAR(24),
    ADD COLUMN formula_maintenance_calories NUMERIC(10, 2),
    ADD COLUMN calculator_observation_basis JSONB;

ALTER TABLE nutrition_plans
    ADD CONSTRAINT ck_nutrition_plans_delta_source CHECK (
        daily_energy_delta_source IS NULL OR daily_energy_delta_source IN (
            'FORMULA_WIZARD', 'OBSERVED_WIZARD', 'RECALIBRATION_AUDIT'
        )
    ),
    ADD CONSTRAINT ck_nutrition_plans_maintenance_source CHECK (
        calculator_maintenance_source IS NULL OR calculator_maintenance_source IN ('FORMULA', 'OBSERVED')
    ),
    ADD CONSTRAINT ck_nutrition_plans_formula_maintenance_range CHECK (
        formula_maintenance_calories IS NULL OR formula_maintenance_calories BETWEEN 800 AND 20000
    ),
    ADD CONSTRAINT ck_nutrition_plans_observation_basis_object CHECK (
        calculator_observation_basis IS NULL OR jsonb_typeof(calculator_observation_basis) = 'object'
    );

UPDATE nutrition_plans
SET daily_energy_delta_source = 'FORMULA_WIZARD',
    calculator_maintenance_source = 'FORMULA',
    formula_maintenance_calories = maintenance_calories
WHERE daily_energy_delta IS NOT NULL
  AND calculator_formula IS NOT NULL
  AND calculator_formula_version IS NOT NULL
  AND maintenance_calories IS NOT NULL
  AND weekly_weight_change_kg IS NOT NULL;

-- The recalibration audit captured the exact intended delta. It is only safe
-- to recover while every current target still matches the audit snapshot.
WITH parsed_audits AS MATERIALIZED (
    SELECT
        s.*,
        CASE
            WHEN s.basis ->> 'intendedDailyEnergyDelta' ~ '^-?[0-9]+([.][0-9]+)?$'
                THEN (s.basis ->> 'intendedDailyEnergyDelta')::NUMERIC
        END AS intended_delta
    FROM plan_recalibration_suggestions s
),
consistent_plans AS (
    SELECT nutrition_plan_id
    FROM parsed_audits
    WHERE intended_delta BETWEEN -10000 AND 10000
    GROUP BY nutrition_plan_id
    HAVING COUNT(DISTINCT intended_delta) = 1
),
recoverable AS (
    SELECT DISTINCT ON (p.id)
        p.id,
        s.intended_delta::NUMERIC(10, 2) AS intended_delta
    FROM nutrition_plans p
    JOIN parsed_audits s ON s.nutrition_plan_id = p.id
    JOIN consistent_plans consistent ON consistent.nutrition_plan_id = p.id
    WHERE p.daily_energy_delta IS NULL
      AND p.daily_energy_delta_source IS NULL
      AND s.intended_delta BETWEEN -10000 AND 10000
      AND p.calories = CASE WHEN s.status = 'ACCEPTED' THEN s.suggested_calories ELSE s.previous_calories END
      AND p.protein = CASE WHEN s.status = 'ACCEPTED' THEN s.suggested_protein ELSE s.previous_protein END
      AND p.carbs = CASE WHEN s.status = 'ACCEPTED' THEN s.suggested_carbs ELSE s.previous_carbs END
      AND p.fat = CASE WHEN s.status = 'ACCEPTED' THEN s.suggested_fat ELSE s.previous_fat END
    ORDER BY p.id, s.created_at DESC, s.id DESC
)
UPDATE nutrition_plans p
SET daily_energy_delta = recoverable.intended_delta,
    daily_energy_delta_source = 'RECALIBRATION_AUDIT'
FROM recoverable
WHERE p.id = recoverable.id;
