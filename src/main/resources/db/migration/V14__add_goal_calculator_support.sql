alter table user_profiles
    add column sex varchar(16),
    add column birth_date date,
    add column height_cm numeric(6,2),
    add column current_weight_kg numeric(6,3),
    add column target_weight_kg numeric(6,3),
    add column daily_movement_level varchar(32),
    add column workout_frequency varchar(32),
    add column goal_type varchar(32),
    add constraint ck_user_profiles_calculator_sex check (
        sex is null
        or sex in ('FEMALE', 'MALE')
    ) not valid,
    add constraint ck_user_profiles_height_range check (
        height_cm is null
        or height_cm between 50 and 300
    ) not valid,
    add constraint ck_user_profiles_current_weight_range check (
        current_weight_kg is null
        or current_weight_kg between 20 and 500
    ) not valid,
    add constraint ck_user_profiles_target_weight_range check (
        target_weight_kg is null
        or target_weight_kg between 20 and 500
    ) not valid,
    add constraint ck_user_profiles_daily_movement_level check (
        daily_movement_level is null
        or daily_movement_level in ('SEDENTARY', 'LIGHT', 'MODERATE', 'ACTIVE', 'VERY_ACTIVE')
    ) not valid,
    add constraint ck_user_profiles_workout_frequency check (
        workout_frequency is null
        or workout_frequency in ('ZERO_DAYS', 'ONE_TO_TWO_DAYS', 'THREE_TO_FOUR_DAYS', 'FIVE_TO_SIX_DAYS', 'DAILY')
    ) not valid,
    add constraint ck_user_profiles_goal_type check (
        goal_type is null
        or goal_type in ('LOSE_WEIGHT', 'MAINTAIN_WEIGHT', 'GAIN_WEIGHT')
    ) not valid;

alter table nutrition_goals
    add column calculator_formula varchar(80),
    add column calculator_formula_version varchar(40),
    add column calculator_sex varchar(16),
    add column calculator_birth_date date,
    add column calculator_height_cm numeric(6,2),
    add column calculator_current_weight_kg numeric(6,3),
    add column calculator_target_weight_kg numeric(6,3),
    add column calculator_daily_movement_level varchar(32),
    add column calculator_workout_frequency varchar(32),
    add column calculator_goal_type varchar(32),
    add column maintenance_calories numeric(10,2),
    add column target_calories numeric(10,2),
    add column activity_factor numeric(6,3),
    add column daily_energy_delta numeric(10,2),
    add column weekly_weight_change_kg numeric(8,3),
    add column estimated_weeks_min integer,
    add column estimated_weeks_max integer,
    add column estimated_target_date date,
    add column recommended_protein numeric(10,3),
    add column recommended_carbs numeric(10,3),
    add column recommended_fat numeric(10,3),
    add column recommended_fiber numeric(10,3),
    add column safety_warning_codes text[],
    add constraint ck_nutrition_goals_calculator_sex check (
        calculator_sex is null
        or calculator_sex in ('FEMALE', 'MALE')
    ) not valid,
    add constraint ck_nutrition_goals_calculator_height_range check (
        calculator_height_cm is null
        or calculator_height_cm between 50 and 300
    ) not valid,
    add constraint ck_nutrition_goals_calculator_current_weight_range check (
        calculator_current_weight_kg is null
        or calculator_current_weight_kg between 20 and 500
    ) not valid,
    add constraint ck_nutrition_goals_calculator_target_weight_range check (
        calculator_target_weight_kg is null
        or calculator_target_weight_kg between 20 and 500
    ) not valid,
    add constraint ck_nutrition_goals_calculator_daily_movement_level check (
        calculator_daily_movement_level is null
        or calculator_daily_movement_level in ('SEDENTARY', 'LIGHT', 'MODERATE', 'ACTIVE', 'VERY_ACTIVE')
    ) not valid,
    add constraint ck_nutrition_goals_calculator_workout_frequency check (
        calculator_workout_frequency is null
        or calculator_workout_frequency in ('ZERO_DAYS', 'ONE_TO_TWO_DAYS', 'THREE_TO_FOUR_DAYS', 'FIVE_TO_SIX_DAYS', 'DAILY')
    ) not valid,
    add constraint ck_nutrition_goals_calculator_goal_type check (
        calculator_goal_type is null
        or calculator_goal_type in ('LOSE_WEIGHT', 'MAINTAIN_WEIGHT', 'GAIN_WEIGHT')
    ) not valid,
    add constraint ck_nutrition_goals_calculator_calories_range check (
        (maintenance_calories is null or maintenance_calories between 800 and 20000)
        and (target_calories is null or target_calories between 800 and 20000)
    ) not valid,
    add constraint ck_nutrition_goals_activity_factor_range check (
        activity_factor is null
        or activity_factor between 1 and 3
    ) not valid,
    add constraint ck_nutrition_goals_energy_delta_range check (
        daily_energy_delta is null
        or daily_energy_delta between -10000 and 10000
    ) not valid,
    add constraint ck_nutrition_goals_weekly_weight_change_range check (
        weekly_weight_change_kg is null
        or weekly_weight_change_kg between -10 and 10
    ) not valid,
    add constraint ck_nutrition_goals_estimated_weeks check (
        (estimated_weeks_min is null or estimated_weeks_min between 0 and 2600)
        and (estimated_weeks_max is null or estimated_weeks_max between 0 and 2600)
        and (
            estimated_weeks_min is null
            or estimated_weeks_max is null
            or estimated_weeks_min <= estimated_weeks_max
        )
    ) not valid,
    add constraint ck_nutrition_goals_recommended_macros_range check (
        (recommended_protein is null or recommended_protein between 0 and 2000)
        and (recommended_carbs is null or recommended_carbs between 0 and 2000)
        and (recommended_fat is null or recommended_fat between 0 and 2000)
        and (recommended_fiber is null or recommended_fiber between 0 and 500)
    ) not valid,
    add constraint ck_nutrition_goals_safety_warning_codes_values check (
        safety_warning_codes is null
        or (
            array_position(safety_warning_codes, null) is null
            and array_position(safety_warning_codes, '') is null
        )
    ) not valid;
