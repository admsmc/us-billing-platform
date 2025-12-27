package com.example.usbilling.payroll.engine.pub15t

import com.example.usbilling.payroll.model.EmployeeSnapshot
import java.time.LocalDate

/**
 * Helpers for deriving [WithholdingProfile] instances from [EmployeeSnapshot]
 * regardless of the underlying W-4 regime.
 */
object WithholdingProfiles {

    /**
     * Construct a [WithholdingProfile] for the given [employee], delegating to
     * [LegacyW4Bridge] when a legacy W-4 is in use.
     */
    fun profileFor(employee: EmployeeSnapshot): WithholdingProfile {
        val explicitVersion = employee.w4Version

        return when {
            explicitVersion == W4Version.LEGACY_PRE_2020 ->
                LegacyW4Bridge.fromLegacy(employee)

            explicitVersion == W4Version.MODERN_2020_PLUS ->
                fromModern(employee)

            // Infer version when not explicitly set: presence of legacy fields
            // wins, otherwise fall back to modern.
            employee.legacyAllowances != null ||
                employee.legacyAdditionalWithholdingPerPeriod != null ||
                employee.legacyMaritalStatus != null ->
                LegacyW4Bridge.fromLegacy(employee)

            else -> fromModern(employee)
        }
    }

    private fun fromModern(employee: EmployeeSnapshot): WithholdingProfile {
        val firstPaidBefore2020 = deriveFirstPaidBefore2020(employee)

        return WithholdingProfile(
            filingStatus = employee.filingStatus,
            w4Version = W4Version.MODERN_2020_PLUS,
            step3AnnualCredit = employee.w4AnnualCreditAmount,
            step4OtherIncomeAnnual = employee.w4OtherIncomeAnnual,
            step4DeductionsAnnual = employee.w4DeductionsAnnual,
            extraWithholdingPerPeriod = employee.additionalWithholdingPerPeriod,
            step2MultipleJobs = employee.w4Step2MultipleJobs,
            federalWithholdingExempt = employee.federalWithholdingExempt,
            isNonresidentAlien = employee.isNonresidentAlien,
            firstPaidBefore2020 = firstPaidBefore2020,
        )
    }

    private fun deriveFirstPaidBefore2020(employee: EmployeeSnapshot): Boolean? {
        val cutoff = LocalDate.of(2020, 1, 1)
        val effective = employee.w4EffectiveDate
        val hire = employee.hireDate

        return when {
            effective != null -> effective.isBefore(cutoff)
            hire != null -> hire.isBefore(cutoff)
            else -> null
        }
    }
}
