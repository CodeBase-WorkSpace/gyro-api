insert into user_profiles (user_id, created_at, updated_at)
select users.id, now(), now()
from users
where not exists (
    select 1
    from user_profiles
    where user_profiles.user_id = users.id
);
