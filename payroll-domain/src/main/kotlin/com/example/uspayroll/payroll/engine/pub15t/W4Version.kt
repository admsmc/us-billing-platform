package com.example.uspayroll.payroll.engine.pub15t

/** Indicates which IRS W-4 regime applies to an employee. */
enum class W4Version {
    /** 2019 and earlier Forms W-4. */
    LEGACY_PRE_2020,

    /** 2020 and later redesigned Forms W-4. */
    MODERN_2020_PLUS,
}
