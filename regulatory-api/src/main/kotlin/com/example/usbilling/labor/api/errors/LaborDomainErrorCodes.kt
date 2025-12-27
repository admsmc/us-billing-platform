package com.example.usbilling.labor.api.errors

/**
 * Stable, machine-readable domain error codes for the Labor bounded context.
 *
 * Format: `labor.<noun>_<reason>`
 * Example: `labor.standards_not_found`
 */
object LaborDomainErrorCodes {

    const val STANDARDS_NOT_FOUND: String = "labor.standards_not_found"
    const val STANDARDS_UNAVAILABLE: String = "labor.standards_unavailable"

    const val INVALID_STATE_CODE: String = "labor.invalid_state_code"
    const val INVALID_HOME_STATE: String = "labor.invalid_home_state"
    const val INVALID_WORK_STATE: String = "labor.invalid_work_state"

    const val CONFIG_INVALID: String = "labor.config_invalid"
}
