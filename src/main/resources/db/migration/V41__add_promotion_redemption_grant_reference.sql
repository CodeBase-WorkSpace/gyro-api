alter table promotion_redemptions add column manual_grant_id uuid references manual_grants (id);

alter table promotion_redemptions drop constraint ck_promotion_redemption_reference;
alter table promotion_redemptions add constraint ck_promotion_redemption_reference check (
    subscription_id is not null or payment_attempt_id is not null or invoice_id is not null or manual_grant_id is not null
);

create unique index uk_promotion_redemption_grant on promotion_redemptions (promotion_id, manual_grant_id)
    where manual_grant_id is not null;
create index idx_promotion_redemptions_grant on promotion_redemptions (manual_grant_id);
