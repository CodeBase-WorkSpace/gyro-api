alter table foods add column brand_name varchar(255);
alter table foods add column normalized_brand_name varchar(255);
alter table foods add column lock_version integer not null default 0;

create index idx_foods_normalized_brand_name on foods (normalized_brand_name) where normalized_brand_name is not null;
