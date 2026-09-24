alter table nutrition_goals
    rename to nutrition_plans;

alter table goal_schedules
    rename to plan_schedules;

alter table nutrition_plans
    rename column effective_date to start_date;

alter table plan_schedules
    rename column nutrition_goal_id to nutrition_plan_id;

alter table nutrition_plans
    rename constraint nutrition_goals_pkey to nutrition_plans_pkey;

alter table nutrition_plans
    rename constraint nutrition_goals_user_id_fkey to nutrition_plans_user_id_fkey;

alter table nutrition_plans
    rename constraint uq_nutrition_goals_user_effective_date to uq_nutrition_plans_user_start_date;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_calories_positive to ck_nutrition_plans_calories_positive;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_macros_non_negative to ck_nutrition_plans_macros_non_negative;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_fiber_non_negative to ck_nutrition_plans_fiber_non_negative;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_target_weight_positive to ck_nutrition_plans_target_weight_positive;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_target_weight_unit to ck_nutrition_plans_target_weight_unit;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_target_weight_pair to ck_nutrition_plans_target_weight_pair;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_calculator_sex to ck_nutrition_plans_calculator_sex;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_calculator_height_range to ck_nutrition_plans_calculator_height_range;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_calculator_current_weight_range to ck_nutrition_plans_calculator_current_weight_range;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_calculator_target_weight_range to ck_nutrition_plans_calculator_target_weight_range;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_calculator_daily_movement_level to ck_nutrition_plans_calculator_daily_movement_level;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_calculator_workout_frequency to ck_nutrition_plans_calculator_workout_frequency;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_calculator_goal_type to ck_nutrition_plans_calculator_goal_type;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_calculator_calories_range to ck_nutrition_plans_calculator_calories_range;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_activity_factor_range to ck_nutrition_plans_activity_factor_range;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_energy_delta_range to ck_nutrition_plans_energy_delta_range;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_weekly_weight_change_range to ck_nutrition_plans_weekly_weight_change_range;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_estimated_weeks to ck_nutrition_plans_estimated_weeks;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_recommended_macros_range to ck_nutrition_plans_recommended_macros_range;

alter table nutrition_plans
    rename constraint ck_nutrition_goals_safety_warning_codes_values to ck_nutrition_plans_safety_warning_codes_values;

alter index idx_nutrition_goals_user_effective_date_desc
    rename to idx_nutrition_plans_user_start_date_desc;

alter table plan_schedules
    rename constraint goal_schedules_pkey to plan_schedules_pkey;

alter table plan_schedules
    rename constraint goal_schedules_user_id_fkey to plan_schedules_user_id_fkey;

alter table plan_schedules
    rename constraint goal_schedules_nutrition_goal_id_fkey to plan_schedules_nutrition_plan_id_fkey;

alter table plan_schedules
    rename constraint uq_goal_schedules_goal to uq_plan_schedules_plan;

alter table plan_schedules
    rename constraint ck_goal_schedules_type to ck_plan_schedules_type;

alter table plan_schedules
    rename constraint ck_goal_schedules_active_range to ck_plan_schedules_active_range;

alter table plan_schedules
    rename constraint ck_goal_schedules_weekly_calorie_budget to ck_plan_schedules_weekly_calorie_budget;

alter table plan_schedules
    rename constraint ck_goal_schedules_json_objects to ck_plan_schedules_json_objects;

alter table plan_schedules
    rename constraint ck_goal_schedules_macro_adjustment_mode to ck_plan_schedules_macro_adjustment_mode;

alter table plan_schedules
    rename constraint ck_goal_schedules_flat_defaults to ck_plan_schedules_flat_defaults;

alter index idx_goal_schedules_user_active_range
    rename to idx_plan_schedules_user_active_range;

alter index idx_goal_schedules_user_type
    rename to idx_plan_schedules_user_type;
