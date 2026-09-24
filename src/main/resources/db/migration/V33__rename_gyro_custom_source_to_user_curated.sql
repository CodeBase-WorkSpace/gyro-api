alter table foods drop constraint ck_foods_source;

update foods set source = 'USER_CURATED' where source = 'GYRO_CUSTOM';
update food_source_metadata set source = 'USER_CURATED' where source = 'GYRO_CUSTOM';

alter table foods add constraint ck_foods_source
    check (source in ('USDA_FDC', 'USER_CURATED', 'GYRO_CURATED'));
