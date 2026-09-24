insert into food_localizations (
    food_id,
    locale,
    display_name,
    normalized_display_name,
    source,
    review_status,
    created_at,
    updated_at
)
select
    f.id,
    'fa',
    'اسنک پفکی ذرت با طعم پنیر',
    'اسنک پفکی ذرت با طعم پنیر',
    'GYRO_CURATED',
    'REVIEWED',
    now(),
    now()
from foods f
where f.public_id = 'food_usda_sr_legacy_167949'
on conflict (food_id, locale) do update
set
    display_name = excluded.display_name,
    normalized_display_name = excluded.normalized_display_name,
    source = excluded.source,
    review_status = excluded.review_status,
    updated_at = now()
where food_localizations.review_status <> 'REVIEWED'
   or food_localizations.display_name like 'Snacks,%';
