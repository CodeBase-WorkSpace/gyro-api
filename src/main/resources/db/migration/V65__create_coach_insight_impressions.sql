create table coach_insight_impressions
(
    user_id uuid not null references users (id) on delete cascade,
    kind varchar(80) not null,
    shown_on date not null,
    primary key (user_id, kind, shown_on)
);

create index idx_coach_insight_impressions_user_shown_on
    on coach_insight_impressions (user_id, shown_on desc);

create trigger trg_coach_insight_impressions_block_deleted_account_write
    before insert or update on coach_insight_impressions
    for each row execute function gyro_reject_write_during_account_deletion('user_id');
