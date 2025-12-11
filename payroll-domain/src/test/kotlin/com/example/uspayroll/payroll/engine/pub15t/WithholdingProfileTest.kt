package com.example.uspayroll.payroll.engine.pub15t

import com.example.uspayroll.payroll.model.FilingStatus
import com.example.uspayroll.shared.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class WithholdingProfileTest {

    @Test
    fun `can construct profile with expected defaults`() {
        val profile = WithholdingProfile(
            filingStatus = FilingStatus.SINGLE,
            w4Version = W4Version.MODERN_2020_PLUS,
        )

        assertEquals(FilingStatus.SINGLE, profile.filingStatus)
        assertEquals(W4Version.MODERN_2020_PLUS, profile.w4Version)
        assertNull(profile.step3AnnualCredit)
        assertNull(profile.step4OtherIncomeAnnual)
        assertNull(profile.step4DeductionsAnnual)
        assertNull(profile.extraWithholdingPerPeriod)
        assertFalse(profile.step2MultipleJobs)
        assertFalse(profile.federalWithholdingExempt)
        assertFalse(profile.isNonresidentAlien)
    }

    @Test
    fun `can construct profile with explicit W-4 fields`() {
        val profile = WithholdingProfile(
            filingStatus = FilingStatus.MARRIED,
            w4Version = W4Version.MODERN_2020_PLUS,
            step3AnnualCredit = Money(1_000_00L),
            step4OtherIncomeAnnual = Money(2_000_00L),
            step4DeductionsAnnual = Money(500_00L),
            extraWithholdingPerPeriod = Money(25_00L),
            step2MultipleJobs = true,
            federalWithholdingExempt = false,
            isNonresidentAlien = true,
            firstPaidBefore2020 = false,
        )

        assertEquals(FilingStatus.MARRIED, profile.filingStatus)
        assertEquals(W4Version.MODERN_2020_PLUS, profile.w4Version)
        assertEquals(Money(1_000_00L), profile.step3AnnualCredit)
        assertEquals(Money(2_000_00L), profile.step4OtherIncomeAnnual)
        assertEquals(Money(500_00L), profile.step4DeductionsAnnual)
        assertEquals(Money(25_00L), profile.extraWithholdingPerPeriod)
    }
}
