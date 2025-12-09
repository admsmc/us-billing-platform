package com.example.uspayroll.payroll.model

import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import kotlin.test.Test
import kotlin.test.assertEquals

class PayScheduleAndSalaryAllocationPolicyTest {

    @Test
    fun `periodsPerYearFor maps frequencies to expected counts`() {
        val employerId = EmployerId("emp-test")

        fun schedule(freq: PayFrequency) = PaySchedule.defaultFor(employerId, freq)

        assertEquals(52, schedule(PayFrequency.WEEKLY).periodsPerYear)
        assertEquals(26, schedule(PayFrequency.BIWEEKLY).periodsPerYear)
        assertEquals(13, schedule(PayFrequency.FOUR_WEEKLY).periodsPerYear)
        assertEquals(24, schedule(PayFrequency.SEMI_MONTHLY).periodsPerYear)
        assertEquals(12, schedule(PayFrequency.MONTHLY).periodsPerYear)
        assertEquals(4, schedule(PayFrequency.QUARTERLY).periodsPerYear)
        assertEquals(1, schedule(PayFrequency.ANNUAL).periodsPerYear)
    }

    @Test
    fun `even allocation matches simple division for clean case`() {
        val employerId = EmployerId("emp-even")
        val schedule = PaySchedule.defaultFor(employerId, PayFrequency.BIWEEKLY) // 26 periods
        val annual = Money(260_000_00L) // $260,000

        val perPeriod = SalaryAllocationPolicy.EvenAllocation
            .perPeriodAmount(annual, schedule)

        // 260,000 / 26 = 10,000 per period
        assertEquals(10_000_00L, perPeriod.amount)
    }

    @Test
    fun `remainder-aware even allocation preserves annual total for non-even case`() {
        val employerId = EmployerId("emp-rem")
        val schedule = PaySchedule(
            employerId = employerId,
            frequency = PayFrequency.SEMI_MONTHLY,
            periodsPerYear = 24,
        )
        val annual = Money(100_000_00L) // $100,000, 24 does not divide evenly into cents

        val allocation = RemainderAwareEvenAllocation.compute(annual, schedule)

        // Base per-period amount should be floor(annual / periodsPerYear)
        val expectedBaseCents = annual.amount / schedule.periodsPerYear
        assertEquals(expectedBaseCents, allocation.basePerPeriod.amount)

        // Sum of allocated amounts across all periods must equal the annual amount
        val totalAllocated = (0 until schedule.periodsPerYear).sumOf { index ->
            allocation.amountForPeriod(index, schedule).amount
        }
        assertEquals(annual.amount, totalAllocated)
    }
}
