-- Scope remains useful for request namespacing, but ownership must be queryable exactly.
alter table idempotency_keys add column user_id uuid references users (id);

-- Existing authenticated mutation scopes embed their owner's UUID. Public scopes remain null.
update idempotency_keys
set user_id = substring(scope from '([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})')::uuid
where scope ~ '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';

create index idx_idempotency_keys_user_id on idempotency_keys (user_id) where user_id is not null;
