-- Pay schedules and pay period generation
--
-- A pay schedule is an employer-defined recurrence pattern that can be used to
-- deterministically generate pay periods.

CREATE TABLE IF NOT EXISTS pay_schedule (
    employer_id              VARCHAR(64)  NOT NULL,
    id                       VARCHAR(64)  NOT NULL,

    frequency                VARCHAR(32)  NOT NULL,

    -- The first start date for the recurrence (anchor). Generation proceeds forward
    -- from this date.
    first_start_date         DATE         NOT NULL,

    -- Check date is derived as: check_date = period_end_date + check_date_offset_days.
    check_date_offset_days   INTEGER      NOT NULL DEFAULT 0,

    -- For SEMI_MONTHLY schedules, the first period in each month ends on this day.
    -- (e.g., 15 => periods are 1-15 and 16-end_of_month). Required for SEMI_MONTHLY.
    semi_monthly_first_end_day INTEGER    NULL,

    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_pay_schedule PRIMARY KEY (employer_id, id)
);

CREATE INDEX IF NOT EXISTS idx_pay_schedule_employer
    ON pay_schedule (employer_id);