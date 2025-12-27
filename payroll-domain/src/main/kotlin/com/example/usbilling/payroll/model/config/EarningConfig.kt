package com.example.usbilling.payroll.model.config

import com.example.usbilling.payroll.model.EarningCategory
import com.example.usbilling.payroll.model.EarningCode
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money

/**
 * Employer-configurable definition of an earning code.
 * The system owns the categories; employers choose codes, names, and parameters.
 */
data class EarningDefinition(
    val code: EarningCode,
    val displayName: String,
    val category: EarningCategory,
    val defaultRate: Money? = null,
    val overtimeMultiplier: Double? = null,
)

/**
 * Port interface for looking up earning definitions per employer.
 * Implementations live in services (e.g. worker/config service), not in the domain.
 */
interface EarningConfigRepository {
    fun findByEmployerAndCode(employerId: UtilityId, code: EarningCode): EarningDefinition?
}
