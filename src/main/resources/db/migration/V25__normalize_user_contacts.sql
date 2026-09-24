do $$
begin
    if exists (
        select 1
        from users
        where email is not null
          and lower(trim(email)) !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
    ) then
        raise exception 'Invalid email values exist. Clean data before applying V25.';
    end if;

    if exists (
        select 1
        from (
            select lower(trim(email)) as normalized_email
            from users
            where email is not null
            group by lower(trim(email))
            having count(*) > 1
        ) duplicates
    ) then
        raise exception 'Duplicate users would conflict after email normalization.';
    end if;

    if exists (
        select 1
        from users
        where phone_number is not null
          and translate(
              translate(trim(phone_number), '۰۱۲۳۴۵۶۷۸۹', '0123456789'),
              '٠١٢٣٤٥٦٧٨٩',
              '0123456789'
          ) !~ '^(09[0-9]{9}|989[0-9]{9}|\+989[0-9]{9})$'
    ) then
        raise exception 'Invalid phone_number values exist. Clean data before applying V25.';
    end if;

    if exists (
        select 1
        from (
            select
                case
                    when normalized_phone_number ~ '^09[0-9]{9}$' then '+98' || substring(normalized_phone_number from 2)
                    when normalized_phone_number ~ '^989[0-9]{9}$' then '+' || normalized_phone_number
                    when normalized_phone_number ~ '^\+989[0-9]{9}$' then normalized_phone_number
                end as canonical_phone_number
            from (
                select translate(
                    translate(trim(phone_number), '۰۱۲۳۴۵۶۷۸۹', '0123456789'),
                    '٠١٢٣٤٥٦٧٨٩',
                    '0123456789'
                ) as normalized_phone_number
                from users
                where phone_number is not null
            ) phones
            group by canonical_phone_number
            having count(*) > 1
        ) duplicates
    ) then
        raise exception 'Duplicate users would conflict after phone normalization.';
    end if;
end $$;

update users
set email = lower(trim(email))
where email is not null;

update users
set phone_number =
    case
        when normalized_phone_number ~ '^09[0-9]{9}$' then '+98' || substring(normalized_phone_number from 2)
        when normalized_phone_number ~ '^989[0-9]{9}$' then '+' || normalized_phone_number
        when normalized_phone_number ~ '^\+989[0-9]{9}$' then normalized_phone_number
    end
from (
    select
        id,
        translate(
            translate(trim(phone_number), '۰۱۲۳۴۵۶۷۸۹', '0123456789'),
            '٠١٢٣٤٥٦٧٨٩',
            '0123456789'
        ) as normalized_phone_number
    from users
    where phone_number is not null
) normalized_users
where users.id = normalized_users.id;

alter table users
    add constraint ck_users_email_normalized
    check (
        email is null
        or (
            email = lower(trim(email))
            and email ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'
        )
    );

alter table users
    add constraint ck_users_phone_number_iran_e164
    check (phone_number is null or phone_number ~ '^\+989[0-9]{9}$');

create unique index if not exists uk_users_email_normalized
    on users (email)
    where email is not null;

create unique index if not exists uk_users_phone_number_normalized
    on users (phone_number)
    where phone_number is not null;
