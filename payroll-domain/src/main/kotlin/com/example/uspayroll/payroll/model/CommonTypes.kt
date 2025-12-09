package com.example.uspayroll.payroll.model

@JvmInline
value class EarningCode(val value: String)

@JvmInline
value class DeductionCode(val value: String)

@JvmInline
value class EmployerContributionCode(val value: String)

@JvmInline
value class Percent(val value: Double) // 0.0 - 1.0

/**
 * System-defined semantic categories for earnings.
 * Employers can define many codes, but must map each to one of these.
 */
enum class EarningCategory {
    REGULAR,
    OVERTIME,
    BONUS,
    COMMISSION,
    FRINGE_TAXABLE,
    FRINGE_NON_TAXABLE,
    REIMBURSEMENT_NON_TAXABLE,
    SUPPLEMENTAL,
    HOLIDAY,
    IMPUTED,
    TIPS,
}

/**
 * High-level labor standards context values used for FLSA-style checks
 * (minimum wage floors, youth wage rules, tip credit thresholds, etc.).
 *
 * This is intentionally small and federal-focused for now; state/local
 * overlays can be added later without changing the engine boundary.
 */
data class LaborStandardsContext(
    /** Federal minimum wage floor per hour (e.g., $7.25). */
    val federalMinimumWage: com.example.uspayroll.shared.Money,
    /**
     * Youth minimum wage cash rate per hour, if allowed (e.g., $4.25), or
     * null if not in effect.
     */
    val youthMinimumWage: com.example.uspayroll.shared.Money? = null,
    /** Maximum age in whole years for youth minimum wage eligibility. */
    val youthMaxAgeYears: Int? = null,
    /** Maximum number of consecutive calendar days from hire for youth wage. */
    val youthMaxConsecutiveDaysFromHire: Int? = null,
    /**
     * Federal tipped-employee cash minimum per hour (e.g., $2.13) for
     * employees who customarily and regularly receive tips.
     */
    val federalTippedCashMinimum: com.example.uspayroll.shared.Money? = null,
    /** Monthly tip threshold for classification as a tipped employee. */
    val tippedMonthlyThreshold: com.example.uspayroll.shared.Money? = null,
)
