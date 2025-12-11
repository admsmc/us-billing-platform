package com.example.uspayroll.payroll.engine.pub15t

import com.example.uspayroll.payroll.model.FilingStatus
import com.example.uspayroll.shared.Money

/**
 * Normalized view of an employee's federal withholding inputs.
 *
 * This abstracts over legacy (pre-2020) and modern (2020+) W-4 forms so that
 * the Pub 15-T engine can operate on a single shape.
 */
data class WithholdingProfile(
    val filingStatus: FilingStatus,
    val w4Version: W4Version,
    /** Optional annual credit amount from W-4 Step 3 (or bridge). */
    val step3AnnualCredit: Money? = null,
    /** Optional annual other income amount from W-4 Step 4(a). */
    val step4OtherIncomeAnnual: Money? = null,
    /** Optional annual deduction amount from W-4 Step 4(b). */
    val step4DeductionsAnnual: Money? = null,
    /** Optional per-period extra withholding from W-4 Step 4(c). */
    val extraWithholdingPerPeriod: Money? = null,
    /** True when the employee's W-4 Step 2 "multiple jobs" box is checked. */
    val step2MultipleJobs: Boolean = false,
    /** True when the employee is exempt from federal income tax withholding. */
    val federalWithholdingExempt: Boolean = false,
    /** True when the employee is treated as a nonresident alien for withholding. */
    val isNonresidentAlien: Boolean = false,
    /**
     * Indicates whether the employee's current W-4 / employment predates the
     * 2020 form redesign for NRA-table selection. May be null when unknown.
     */
    val firstPaidBefore2020: Boolean? = null,
)
