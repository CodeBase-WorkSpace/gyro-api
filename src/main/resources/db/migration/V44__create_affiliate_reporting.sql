alter table promotion_redemptions
    add column discount_percentage_snapshot numeric(5, 2),
    add column commission_percentage_snapshot numeric(5, 2);

create table affiliates
(
    id                    uuid primary key default gen_random_uuid(),
    display_name          varchar(160)   not null,
    status                varchar(24)    not null default 'ACTIVE',
    promotion_id          bigint         not null references promotions (id),
    linked_user_id        uuid references users (id),
    commission_percentage numeric(5, 2)  not null,
    internal_notes        text,
    created_at            timestamptz    not null default now(),
    updated_at            timestamptz    not null default now(),
    version               bigint         not null default 0,

    constraint uk_affiliates_promotion unique (promotion_id),
    constraint uk_affiliates_linked_user unique (linked_user_id),
    constraint ck_affiliates_status check (status in ('ACTIVE', 'INACTIVE')),
    constraint ck_affiliates_commission_rate check (
        commission_percentage > 0 and commission_percentage < 100
    )
);

create index idx_affiliates_status on affiliates (status);

create table affiliate_commissions
(
    id                              uuid primary key default gen_random_uuid(),
    affiliate_id                    uuid           not null references affiliates (id),
    referred_user_id                uuid           not null references users (id),
    invoice_id                      uuid           not null references invoices (id),
    promotion_redemption_id         uuid           not null references promotion_redemptions (id),
    currency                        varchar(3)      not null,
    original_invoice_amount         numeric(12, 2) not null,
    customer_paid_amount            numeric(12, 2) not null,
    discount_percentage_snapshot    numeric(5, 2)  not null,
    commission_percentage_snapshot  numeric(5, 2)  not null,
    earning_amount                  numeric(12, 2) not null,
    status                          varchar(24)    not null default 'EARNED',
    earned_at                       timestamptz    not null,
    created_at                      timestamptz    not null default now(),
    reversed_at                     timestamptz,
    reversal_reason                 text,

    constraint uk_affiliate_commission_invoice unique (invoice_id),
    constraint uk_affiliate_commission_acquisition unique (referred_user_id),
    constraint ck_affiliate_commission_status check (status in ('EARNED', 'REVERSED')),
    constraint ck_affiliate_commission_amounts check (
        original_invoice_amount > 0
        and customer_paid_amount > 0
        and customer_paid_amount <= original_invoice_amount
        and earning_amount > 0
    ),
    constraint ck_affiliate_commission_reversal check (
        (status = 'EARNED' and reversed_at is null and reversal_reason is null)
        or (status = 'REVERSED' and reversed_at is not null and reversal_reason is not null)
    )
);

create index idx_affiliate_commissions_affiliate_earned
    on affiliate_commissions (affiliate_id, earned_at desc)
    where status = 'EARNED';

create index idx_affiliate_commissions_reporting
    on affiliate_commissions (affiliate_id, status, earned_at)
    include (customer_paid_amount, earning_amount);
