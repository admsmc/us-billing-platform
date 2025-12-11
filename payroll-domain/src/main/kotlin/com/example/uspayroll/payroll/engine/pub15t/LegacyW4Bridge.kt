package com.example.uspayroll.payroll.engine.pub15t

import com.example.uspayroll.payroll.model.EmployeeSnapshot
import java.time.LocalDate

/**
 * Utilities for converting legacy (pre-2020) W-4 data into a normalized
 * [WithholdingProfile].
 *
 * The detailed numerical mapping for the IRS "computational bridge" (allowance
 * values, synthetic Step 4(a)/4(b), etc.) will be implemented in a later
 * iteration. For now this bridge focuses on wiring legacy fields into the
 * profile shape in a traceable way so that the Pub 15-T engine can evolve
 * without losing information.
 */
object LegacyW4Bridge {

    /**
     * Build a [WithholdingProfile] from a legacy (pre-2020) W-4 snapshot.
     *
     * This function does not yet apply the full IRS computational bridge; it
     * simply:
     * - Marks the profile as [W4Version.LEGACY_PRE_2020].
     * - Carries through filing status.
     * - Routes legacy additional withholding into the per-period extra field.
     * - Derives a best-effort [WithholdingProfile.firstPaidBefore2020] flag
     *   from [EmployeeSnapshot.w4EffectiveDate] or [EmployeeSnapshot.hireDate].
     */
    fun fromLegacy(employee: EmployeeSnapshot): WithholdingProfile {
        val firstPaidBefore2020 = deriveFirstPaidBefore2020(employee)

        val filingStatus = employee.filingStatus

        // IRS Pub. 15-T 2025 computational bridge constants (in cents).
        // See "How To Treat 2019 and Earlier Forms W-4 as if They Were 2020 or
        // Later Forms W-4" in the Introduction of Pub. 15-T.
        val perAllowanceCents = 4_300_00L

        val step4aCents: Long = if (employee.isNonresidentAlien) {
            // For nonresident aliens using the bridge, Pub. 15-T instructs
            // employers to always enter $4,300 in Step 4(a), regardless of
            // marital status.
            4_300_00L
        } else {
            when (filingStatus) {
                com.example.uspayroll.payroll.model.FilingStatus.MARRIED -> 12_900_00L
                else -> 8_600_00L
            }
        }

        val allowances = (employee.legacyAllowances ?: 0).coerceAtLeast(0)
        val step4bCents = allowances.toLong() * perAllowanceCents

        return WithholdingProfile(
            filingStatus = filingStatus,
            w4Version = W4Version.LEGACY_PRE_2020,
            // Pub. 15-T computational bridge does not define synthetic Step 3
            // values; we leave credits null here.
            step3AnnualCredit = null,
            // Synthetic Step 4(a) and 4(b) per Pub. 15-T bridge for pre-2020 W-4s.
            step4OtherIncomeAnnual = com.example.uspayroll.shared.Money(step4aCents),
            step4DeductionsAnnual = if (step4bCents > 0L) com.example.uspayroll.shared.Money(step4bCents) else null,
            extraWithholdingPerPeriod =
                employee.legacyAdditionalWithholdingPerPeriod
                    ?: employee.additionalWithholdingPerPeriod,
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
