-- Opt-in, synthetic local fixture. This is not a Flyway migration or production data.
begin;

do $$
begin
    if current_database() <> 'contributor_db' or current_user <> 'contributor' then
        raise exception 'Contributor fixture requires the local contributor database and role';
    end if;
end;
$$;

insert into foods (
    public_id, type, source, source_food_id, name, normalized_name,
    data_quality, curation_status, is_searchable
)
values
    ('example-oat-bowl', 'SYSTEM', 'GYRO_CURATED', 'CONTRIBUTOR_DEMO_OATS',
     'Example Oat Bowl', 'example oat bowl', 'UNREVIEWED', 'UNREVIEWED', true),
    ('example-lentil-soup', 'SYSTEM', 'GYRO_CURATED', 'CONTRIBUTOR_DEMO_SOUP',
     'Example Lentil Soup', 'example lentil soup', 'UNREVIEWED', 'UNREVIEWED', true)
on conflict (public_id) do nothing;

insert into food_nutrition_facts (
    food_id, base_quantity, base_unit_id, calories, protein, carbs, fat, fiber, sugar, sodium
)
select f.id, 100, su.id, demo.calories, demo.protein, demo.carbs,
       demo.fat, demo.fiber, demo.sugar, demo.sodium
from (values
    ('example-oat-bowl', 120, 4, 20, 3, 2, 1, 50),
    ('example-lentil-soup', 90, 5, 12, 2, 4, 1, 120)
) as demo(public_id, calories, protein, carbs, fat, fiber, sugar, sodium)
join foods f on f.public_id = demo.public_id
    and f.source_food_id in ('CONTRIBUTOR_DEMO_OATS', 'CONTRIBUTOR_DEMO_SOUP')
join serving_units su on su.code = 'GRAM'
on conflict (food_id) do nothing;

insert into food_localizations (
    food_id, locale, display_name, normalized_display_name, source, review_status
)
select f.id, 'en', f.name, f.normalized_name, 'SYSTEM', 'REVIEWED'
from foods f
where f.public_id in ('example-oat-bowl', 'example-lentil-soup')
  and f.source_food_id in ('CONTRIBUTOR_DEMO_OATS', 'CONTRIBUTOR_DEMO_SOUP')
on conflict (food_id, locale) do nothing;

insert into food_search_terms (
    food_id, locale, term, normalized_term, term_kind, weight, search_vector
)
select f.id, 'en', f.name, f.normalized_name, 'NAME', 1,
       to_tsvector('english', f.normalized_name)
from foods f
where f.public_id in ('example-oat-bowl', 'example-lentil-soup')
  and f.source_food_id in ('CONTRIBUTOR_DEMO_OATS', 'CONTRIBUTOR_DEMO_SOUP')
on conflict (food_id, locale, term_kind, normalized_term) do nothing;

commit;
