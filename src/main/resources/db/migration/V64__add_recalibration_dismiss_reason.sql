alter table plan_recalibration_suggestions
    add column dismiss_reason varchar(32),
    add constraint ck_plan_recalibration_dismiss_reason check (
        dismiss_reason is null
        or dismiss_reason in (
            'TOO_AGGRESSIVE',
            'DOESNT_FEEL_RIGHT',
            'DATA_IS_WRONG',
            'NOT_NOW'
        )
    );
