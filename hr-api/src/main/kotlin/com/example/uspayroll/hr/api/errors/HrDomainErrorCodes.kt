package com.example.uspayroll.hr.api.errors

/**
 * Stable, machine-readable domain error codes for the HR bounded context.
 *
 * Format: `hr.<noun>_<reason>`
 * Example: `hr.employee_not_found`
 */
object HrDomainErrorCodes {

    const val EMPLOYEE_NOT_FOUND: String = "hr.employee_not_found"
    const val PAY_PERIOD_NOT_FOUND: String = "hr.pay_period_not_found"

    const val GARNISHMENT_ORDER_INVALID: String = "hr.garnishment_order_invalid"
    const val GARNISHMENT_WITHHOLDING_INVALID: String = "hr.garnishment_withholding_invalid"

    const val CONFIG_INVALID: String = "hr.config_invalid"
}
