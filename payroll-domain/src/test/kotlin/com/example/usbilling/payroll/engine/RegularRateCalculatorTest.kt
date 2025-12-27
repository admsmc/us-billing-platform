package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillRunId
import com.example.usbilling.shared.BillId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RegularRateCalculatorTest {

    @Test
    fun `additional overtime premium is added for hourly bonus week`() {
        val employerId = UtilityId("emp-rr")
        val employeeId = CustomerId("ee-rr")
        val period = PayPeriod(
            id = "2025-01-W-RR",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 7)),
            checkDate = LocalDate.of(2025, 1, 8),
            frequency = PayFrequency.WEEKLY,
        )

        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Hourly(hourlyRate = Money(20_00L)),
        )

        // 40 regular hours, 10 overtime hours, $100 nondiscretionary bonus.
        val bonus = EarningInput(
            code = EarningCode("BONUS"),
            units = 1.0,
            rate = null,
            amount = Money(100_00L),
        )

        val timeSlice = TimeSlice(
            period = period,
            regularHours = 40.0,
            overtimeHours = 10.0,
            otherEarnings = listOf(bonus),
        )

        val input = PaycheckInput(
            paycheckId = BillId("chk-rr"),
            payRunId = BillRunId("run-rr"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = timeSlice,
            taxContext = TaxContext(),
            priorYtd = YtdSnapshot(year = 2025),
        )

        val result = calculatePaycheckDebug(
            input = input,
            earningConfig = null,
            deductionConfig = null,
        )

        // Base expectations:
        // Regular: 40 * 20 = 800
        // Overtime (default 1.5x): 10 * (20 * 1.5) = 300
        // Bonus: 100
        // Additional OT premium on bonus:
        //   extra = 0.5 * (bonus / totalHours) * otHours
        //         = 0.5 * (100 / 50) * 10 = 10
        // Total gross = 800 + 300 + 100 + 10 = 1,210
        assertEquals(1_210_00L, result.gross.amount)

        val extraLine = result.earnings.firstOrNull { it.code == EarningCode("OT_BONUS_PREMIUM") }
            ?: error("Expected OT_BONUS_PREMIUM line")
        assertEquals(Money(10_00L), extraLine.amount)
    }
}
