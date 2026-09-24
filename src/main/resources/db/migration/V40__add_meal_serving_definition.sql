alter table meals
    add column total_batch_weight numeric(12, 4),
    add column serving_weight numeric(12,4);

alter table meals
    add constraint ck_meals_total_batch_weight check (total_batch_weight is null or total_batch_weight > 0),
    add constraint ck_meals_serving_weight check (serving_weight is null or serving_weight > 0),
    add constraint ck_meals_serving_definition_pair check ((total_batch_weight is null) = (serving_weight is null)),
    add constraint ck_meals_serving_weight_within_batch check (serving_weight is null or serving_weight <= total_batch_weight);
