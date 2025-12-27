package com.example.usbilling.tax.impl

import com.example.usbilling.payroll.model.TaxBasis
import com.example.usbilling.payroll.model.TaxJurisdictionType
import com.example.usbilling.shared.EmployerId
import java.time.LocalDate

/**
 * Raw record shape for a tax rule as stored in the persistence layer.
 *
 * This is intentionally close to the database schema and is adapted into the
 * payroll-domain tax rule model by higher-level catalog adapters.
 */
data class TaxRuleRecord(
    val id: String,
    val jurisdictionType: TaxJurisdictionType,
    val jurisdictionCode: String,
    val basis: TaxBasis,
    val ruleType: RuleType,
    val rate: Double?,
    val annualWageCapCents: Long?,
    val bracketsJson: String?,
    val standardDeductionCents: Long?,
    val additionalWithholdingCents: Long?,
    // Real-world selection fields. These are used by the persistence layer
    // to choose which rules apply for a given employer/date/employee context.
    val employerId: EmployerId? = null,
    val effectiveFrom: LocalDate? = null,
    val effectiveTo: LocalDate? = null,
    val filingStatus: String? = null,
    val residentStateFilter: String? = null,
    val workStateFilter: String? = null,
    val localityFilter: String? = null,
    val fitVariant: String? = null,
) {
    enum class RuleType { FLAT, BRACKETED, WAGE_BRACKET }
}
