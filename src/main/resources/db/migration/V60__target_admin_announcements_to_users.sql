alter table admin_announcements
    add column recipient_user_id uuid;

alter table admin_announcements
    add constraint ck_admin_announcement_recipient_channel
        check (recipient_user_id is null or not target_telegram_channel);

create index idx_admin_announcements_recipient_user
    on admin_announcements (recipient_user_id, created_at desc)
    where recipient_user_id is not null;

comment on column admin_announcements.recipient_user_id is
    'Optional tombstone-safe user UUID for a one-user Web Push announcement; null means all eligible users.';
