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
}
