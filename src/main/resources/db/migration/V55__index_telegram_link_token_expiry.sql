create index idx_notification_telegram_link_tokens_expiry
    on notification_telegram_link_tokens (expires_at);
