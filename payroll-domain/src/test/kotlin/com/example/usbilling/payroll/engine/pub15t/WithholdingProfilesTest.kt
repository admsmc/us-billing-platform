package com.example.usbilling.payroll.engine.pub15t

import com.example.usbilling.payroll.model.BaseCompensation
import com.example.usbilling.payroll.model.EmployeeSnapshot
import com.example.usbilling.payroll.model.EmploymentType
import com.example.usbilling.payroll.model.FilingStatus
import com.example.usbilling.payroll.model.PayFrequency
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WithholdingProfilesTest {

    private fun baseSnapshot(): EmployeeSnapshot = EmployeeSnapshot(
        employerId = UtilityId("EMP-PROFILE"),
        employeeId = CustomerId("EE-PROFILE"),
        homeState = "CA",
        workState = "CA",
        filingStatus = FilingStatus.MARRIED,
        employmentType = EmploymentType.REGULAR,
        baseCompensation = BaseCompensation.Salaried(
            annualSalary = Money(80_000_00L),
            frequency = PayFrequency.BIWEEKLY,
        ),
    )

    @Test
    fun `profileFor uses modern mapping when w4Version is MODERN_2020_PLUS`() {
        val snapshot = baseSnapshot().copy(
            w4Version = W4Version.MODERN_2020_PLUS,
            w4AnnualCreditAmount = Money(1_500_00L),
            w4OtherIncomeAnnual = Money(3_000_00L),
            w4DeductionsAnnual = Money(500_00L),
            additionalWithholdingPerPeriod = Money(20_00L),
            w4Step2MultipleJobs = true,
            federalWithholdingExempt = false,
            isNonresidentAlien = false,
            w4EffectiveDate = LocalDate.of(2024, 1, 1),
        )

        val profile = WithholdingProfiles.profileFor(snapshot)

        assertEquals(FilingStatus.MARRIED, profile.filingStatus)
        assertEquals(W4Version.MODERN_2020_PLUS, profile.w4Version)
        assertEquals(Money(1_500_00L), profile.step3AnnualCredit)
        assertEquals(Money(3_000_00L), profile.step4OtherIncomeAnnual)
        assertEquals(Money(500_00L), profile.step4DeductionsAnnual)
        assertEquals(Money(20_00L), profile.extraWithholdingPerPeriod)
        assertTrue(profile.step2MultipleJobs)
    }

    @Test
    fun `profileFor infers legacy when legacy fields are present`() {
        val snapshot = baseSnapshot().copy(
            w4Version = null,
            legacyAllowances = 1,
            legacyAdditionalWithholdingPerPeriod = Money(15_00L),
            legacyMaritalStatus = "MARRIED",
            w4EffectiveDate = LocalDate.of(2018, 6, 1),
            w4Step2MultipleJobs = false,
            isNonresidentAlien = true,
        )

        val profile = WithholdingProfiles.profileFor(snapshot)

        assertEquals(FilingStatus.MARRIED, profile.filingStatus)
        assertEquals(W4Version.LEGACY_PRE_2020, profile.w4Version)
        assertEquals(Money(15_00L), profile.extraWithholdingPerPeriod)
        assertTrue(profile.isNonresidentAlien)
        assertEquals(true, profile.firstPaidBefore2020)
    }
}
