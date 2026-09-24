ALTER TABLE nutrition_plans
    ADD COLUMN calculator_change_speed VARCHAR(24);

ALTER TABLE nutrition_plans
    ADD CONSTRAINT ck_nutrition_plans_calculator_change_speed CHECK (
        calculator_change_speed IS NULL OR calculator_change_speed IN (
            'CONSERVATIVE', 'BALANCED', 'AGGRESSIVE'
        )
    );

-- Existing snapshots predate persisted speed. Leave them unknown rather than
-- inferring user intent from a derived energy delta.
