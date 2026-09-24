-- Passwordless-first authentication: accounts may exist without a password.
-- Password becomes an optional credential that users can set/change/remove later.
-- No backfill and no placeholder hashes: existing rows keep their hash, new
-- OTP-only accounts are created with a NULL password_hash.
alter table users
    alter column password_hash drop not null;
