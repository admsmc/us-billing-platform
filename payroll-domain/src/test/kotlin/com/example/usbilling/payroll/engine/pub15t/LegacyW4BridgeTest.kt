package com.example.usbilling.payroll.engine.pub15t

import com.example.usbilling.payroll.model.BaseCompensation
import com.example.usbilling.payroll.model.EmployeeSnapshot
import com.example.usbilling.payroll.model.EmploymentType
import com.example.usbilling.payroll.model.FilingStatus
import com.example.usbilling.payroll.model.PayFrequency
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyW4BridgeTest {

    private fun baseLegacySnapshot(hireDate: LocalDate? = null, w4EffectiveDate: LocalDate? = null): EmployeeSnapshot = EmployeeSnapshot(
        employerId = EmployerId("EMP-LEGACY"),
        employeeId = EmployeeId("EE-LEGACY"),
        homeState = "CA",
        workState = "CA",
        filingStatus = FilingStatus.SINGLE,
        employmentType = EmploymentType.REGULAR,
        baseCompensation = BaseCompensation.Salaried(
            annualSalary = Money(50_000_00L),
            frequency = PayFrequency.ANNUAL,
        ),
        hireDate = hireDate,
        w4Version = W4Version.LEGACY_PRE_2020,
        legacyAllowances = 2,
        legacyAdditionalWithholdingPerPeriod = Money(10_00L),
        legacyMaritalStatus = "SINGLE",
        w4EffectiveDate = w4EffectiveDate,
        w4Step2MultipleJobs = true,
        federalWithholdingExempt = false,
        isNonresidentAlien = true,
    )

    @Test
    fun `fromLegacy maps core fields and uses legacy additional withholding`() {
        val snapshot = baseLegacySnapshot()

        val profile = LegacyW4Bridge.fromLegacy(snapshot)

        assertEquals(FilingStatus.SINGLE, profile.filingStatus)
        assertEquals(W4Version.LEGACY_PRE_2020, profile.w4Version)
        // Bridge does not set synthetic Step 3 values.
        assertNull(profile.step3AnnualCredit)
        // For SINGLE legacy W-4 and nonresident alien, Pub. 15-T bridge uses
        // $4,300 in Step 4(a).
        assertEquals(Money(4_300_00L), profile.step4OtherIncomeAnnual)
        // With 2 legacy allowances, Step 4(b) should be 2 * $4,300 = $8,600.
        assertEquals(Money(8_600_00L), profile.step4DeductionsAnnual)
        // Legacy additional withholding should drive the per-period extra.
        assertEquals(Money(10_00L), profile.extraWithholdingPerPeriod)
        assertTrue(profile.step2MultipleJobs)
        assertFalse(profile.federalWithholdingExempt)
        assertTrue(profile.isNonresidentAlien)
    }

    @Test
    fun `fromLegacy derives firstPaidBefore2020 from effective date or hire date`() {
        val beforeCutoff = LocalDate.of(2019, 12, 31)
        val afterCutoff = LocalDate.of(2020, 1, 1)

        val withEffectiveBefore = baseLegacySnapshot(
            hireDate = afterCutoff,
            w4EffectiveDate = beforeCutoff,
        )
        val withHireBefore = baseLegacySnapshot(
            hireDate = beforeCutoff,
            w4EffectiveDate = null,
        )
        val withNoDates = baseLegacySnapshot(
            hireDate = null,
            w4EffectiveDate = null,
        )

        assertEquals(true, LegacyW4Bridge.fromLegacy(withEffectiveBefore).firstPaidBefore2020)
        assertEquals(true, LegacyW4Bridge.fromLegacy(withHireBefore).firstPaidBefore2020)
        assertNull(LegacyW4Bridge.fromLegacy(withNoDates).firstPaidBefore2020)
    }
}
