-- A committed write barrier used while an admin deletion is in progress.
-- Existing access tokens are rejected when this state is observed by JwtAuthFilter.
alter table users drop constraint if exists ck_users_status;

alter table users add constraint ck_users_status
    check (status in ('ACTIVE', 'DISABLED', 'PENDING_VERIFICATION', 'DEACTIVATED', 'DELETION_IN_PROGRESS', 'DELETED'));
