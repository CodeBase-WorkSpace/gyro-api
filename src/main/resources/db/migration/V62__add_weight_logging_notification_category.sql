alter table notification_intents
    drop constraint ck_notification_intent_category;
alter table notification_intents
    add constraint ck_notification_intent_category check (
        category in (
            'MANDATORY_TRANSACTIONAL',
            'OPTIONAL_BILLING',
            'OPTIONAL_FOOD_LOGGING',
            'OPTIONAL_WEIGHT_LOGGING',
            'OPTIONAL_ANNOUNCEMENTS'
        )
    );

alter table notification_preferences
    drop constraint ck_notification_preference_category;
alter table notification_preferences
    add constraint ck_notification_preference_category check (
        category in (
            'OPTIONAL_BILLING',
            'OPTIONAL_FOOD_LOGGING',
            'OPTIONAL_WEIGHT_LOGGING',
            'OPTIONAL_ANNOUNCEMENTS'
        )
    );
