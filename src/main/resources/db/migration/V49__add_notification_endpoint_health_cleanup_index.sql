create index idx_notification_endpoint_health_diagnostic_cleanup
    on notification_endpoint_health (diagnostic_delete_at) where diagnostic_delete_at is not null;
