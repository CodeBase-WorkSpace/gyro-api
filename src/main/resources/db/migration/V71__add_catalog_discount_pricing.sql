-- Make list price and permanent catalog discount first-class price-version data.

alter table subscription_prices
    add column base_amount numeric(12, 2),
    add column discount_percent numeric(5, 2) not null default 0.00;

update subscription_prices
set base_amount = amount
where base_amount is null;

alter table subscription_prices
    alter column base_amount set not null,
    add constraint ck_subscription_price_base_amount_non_negative
        check (base_amount >= 0),
    add constraint ck_subscription_price_discount_percent
        check (discount_percent >= 0 and discount_percent < 100),
    add constraint ck_subscription_price_derived_amount
        check (amount = round(base_amount * (100.00 - discount_percent) / 100.00, 2));

-- Keep legacy insert paths safe during a rolling deployment. New application writes always
-- provide both fields, while an older instance gets the zero-discount equivalent.
create function default_subscription_price_catalog_discount()
returns trigger
language plpgsql
as $$
begin
    if new.base_amount is null then
        new.base_amount := new.amount;
    end if;
    if new.discount_percent is null then
        new.discount_percent := 0.00;
    end if;
    return new;
end;
$$;

create trigger trg_subscription_price_catalog_discount_defaults
before insert on subscription_prices
for each row execute function default_subscription_price_catalog_discount();
