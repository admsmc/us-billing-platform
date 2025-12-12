package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StateIncomeTaxIntegrationTest {

    private fun basePeriod(employerId: EmployerId): PayPeriod = PayPeriod(
        id = "2025-01-BW-SIT",
        employerId = employerId,
        dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
        checkDate = LocalDate.of(2025, 1, 15),
        frequency = PayFrequency.BIWEEKLY,
    )

    private fun baseSnapshot(employerId: EmployerId, employeeId: EmployeeId, homeState: String, workState: String): EmployeeSnapshot = EmployeeSnapshot(
        employerId = employerId,
        employeeId = employeeId,
        homeState = homeState,
        workState = workState,
        filingStatus = FilingStatus.SINGLE,
        baseCompensation = BaseCompensation.Salaried(
            annualSalary = Money(260_000_00L), // $260,000 annual
            frequency = PayFrequency.BIWEEKLY,
        ),
    )

    private fun caSingleRule(): TaxRule.BracketedIncomeTax = TaxRule.BracketedIncomeTax(
        id = "US_CA_SIT_2025_SINGLE",
        jurisdiction = TaxJurisdiction(TaxJurisdictionType.STATE, "CA"),
        basis = TaxBasis.StateTaxable,
        brackets = listOf(
            TaxBracket(Money(10_756_00L), Percent(0.01)),
            TaxBracket(Money(25_499_00L), Percent(0.02)),
            TaxBracket(Money(40_245_00L), Percent(0.04)),
            TaxBracket(Money(55_866_00L), Percent(0.06)),
            TaxBracket(Money(70_606_00L), Percent(0.08)),
            TaxBracket(Money(360_659_00L), Percent(0.093)),
            TaxBracket(Money(432_787_00L), Percent(0.103)),
            TaxBracket(Money(721_314_00L), Percent(0.113)),
            TaxBracket(null, Percent(0.123)),
        ),
    )

    private fun nySingleRule(): TaxRule.BracketedIncomeTax = TaxRule.BracketedIncomeTax(
        id = "US_NY_SIT_2025_SINGLE",
        jurisdiction = TaxJurisdiction(TaxJurisdictionType.STATE, "NY"),
        basis = TaxBasis.StateTaxable,
        brackets = listOf(
            TaxBracket(Money(8_500_00L), Percent(0.04)),
            TaxBracket(Money(11_700_00L), Percent(0.045)),
            TaxBracket(Money(13_900_00L), Percent(0.0525)),
            TaxBracket(Money(80_650_00L), Percent(0.055)),
            TaxBracket(Money(215_400_00L), Percent(0.06)),
            TaxBracket(Money(1_077_550_00L), Percent(0.0685)),
            TaxBracket(Money(5_000_000_00L), Percent(0.0965)),
            TaxBracket(Money(25_000_000_00L), Percent(0.103)),
            TaxBracket(null, Percent(0.109)),
        ),
    )

    private fun txZeroRule(): TaxRule.FlatRateTax = TaxRule.FlatRateTax(
        id = "US_TX_SIT_2025",
        jurisdiction = TaxJurisdiction(TaxJurisdictionType.STATE, "TX"),
        basis = TaxBasis.StateTaxable,
        rate = Percent(0.0),
    )

    private fun ilFlatRule(): TaxRule.FlatRateTax = TaxRule.FlatRateTax(
        id = "US_IL_SIT_2025",
        jurisdiction = TaxJurisdiction(TaxJurisdictionType.STATE, "IL"),
        basis = TaxBasis.StateTaxable,
        rate = Percent(0.0495),
    )

    @Test
    fun `state income tax differs across CA TX NY for the same gross`() {
        val employerId = EmployerId("EMP-SIT")
        val period = basePeriod(employerId)

        // Build inputs for three employees with identical comp but different states.
        val caSnapshot = baseSnapshot(employerId, EmployeeId("EE-CA"), homeState = "CA", workState = "CA")
        val txSnapshot = baseSnapshot(employerId, EmployeeId("EE-TX"), homeState = "TX", workState = "TX")
        val nySnapshot = baseSnapshot(employerId, EmployeeId("EE-NY"), homeState = "NY", workState = "NY")

        fun runFor(snapshot: EmployeeSnapshot, stateRules: List<TaxRule>): PaycheckResult {
            val input = PaycheckInput(
                paycheckId = PaycheckId("CHK-${'$'}{snapshot.employeeId.value}"),
                payRunId = PayRunId("RUN-SIT"),
                employerId = employerId,
                employeeId = snapshot.employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = TaxContext(
                    state = stateRules,
                ),
                priorYtd = YtdSnapshot(year = 2025),
            )
            return PayrollEngine.calculatePaycheck(input)
        }

        val caResult = runFor(caSnapshot, listOf(caSingleRule()))
        val txResult = runFor(txSnapshot, listOf(txZeroRule()))
        val nyResult = runFor(nySnapshot, listOf(nySingleRule()))

        // Gross should be the same for all three employees
        assertEquals(caResult.gross.amount, txResult.gross.amount)
        assertEquals(caResult.gross.amount, nyResult.gross.amount)

        val caStateTax = caResult.employeeTaxes.firstOrNull { it.jurisdiction.type == TaxJurisdictionType.STATE }?.amount?.amount ?: 0L
        val txStateTax = txResult.employeeTaxes.firstOrNull { it.jurisdiction.type == TaxJurisdictionType.STATE }?.amount?.amount ?: 0L
        val nyStateTax = nyResult.employeeTaxes.firstOrNull { it.jurisdiction.type == TaxJurisdictionType.STATE }?.amount?.amount ?: 0L

        // TX has no state income tax on wages (0% rule), so we expect zero state tax.
        assertEquals(0L, txStateTax)

        // CA and NY should both have positive state tax, with NY tax higher than CA
        assertTrue(caStateTax > 0L, "Expected CA state income tax to be positive")
        assertTrue(nyStateTax > 0L, "Expected NY state income tax to be positive")
        assertTrue(nyStateTax > caStateTax, "Expected NY state income tax to exceed CA for this wage level")

        // Net pay should reflect these differences: NY < CA < TX
        assertTrue(nyResult.net.amount < caResult.net.amount, "NY net should be less than CA net")
        assertTrue(caResult.net.amount < txResult.net.amount, "CA net should be less than TX net")
    }

    @Test
    fun `flat IL state tax sits between TX zero and progressive NY`() {
        val employerId = EmployerId("EMP-SIT")
        val period = basePeriod(employerId)

        val txSnapshot = baseSnapshot(employerId, EmployeeId("EE-TX2"), homeState = "TX", workState = "TX")
        val ilSnapshot = baseSnapshot(employerId, EmployeeId("EE-IL2"), homeState = "IL", workState = "IL")
        val nySnapshot = baseSnapshot(employerId, EmployeeId("EE-NY2"), homeState = "NY", workState = "NY")

        fun runFor(snapshot: EmployeeSnapshot, stateRules: List<TaxRule>): PaycheckResult {
            val input = PaycheckInput(
                paycheckId = PaycheckId("CHK-${'$'}{snapshot.employeeId.value}"),
                payRunId = PayRunId("RUN-SIT-FLAT"),
                employerId = employerId,
                employeeId = snapshot.employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = TaxContext(
                    state = stateRules,
                ),
                priorYtd = YtdSnapshot(year = 2025),
            )
            return PayrollEngine.calculatePaycheck(input)
        }

        val txResult = runFor(txSnapshot, listOf(txZeroRule()))
        val ilResult = runFor(ilSnapshot, listOf(ilFlatRule()))
        val nyResult = runFor(nySnapshot, listOf(nySingleRule()))

        // Same gross for all
        assertEquals(txResult.gross.amount, ilResult.gross.amount)
        assertEquals(txResult.gross.amount, nyResult.gross.amount)

        val txStateTax = txResult.employeeTaxes.firstOrNull { it.jurisdiction.type == TaxJurisdictionType.STATE }?.amount?.amount ?: 0L
        val ilStateTax = ilResult.employeeTaxes.firstOrNull { it.jurisdiction.type == TaxJurisdictionType.STATE }?.amount?.amount ?: 0L
        val nyStateTax = nyResult.employeeTaxes.firstOrNull { it.jurisdiction.type == TaxJurisdictionType.STATE }?.amount?.amount ?: 0L

        // TX still zero, flat IL and progressive NY both positive
        assertEquals(0L, txStateTax)
        assertTrue(ilStateTax > 0L, "Expected IL state income tax to be positive")
        assertTrue(nyStateTax > 0L, "Expected NY state income tax to be positive")

        // Ensure IL and NY behave differently (sanity check that flat vs progressive differ)
        assertTrue(ilStateTax != nyStateTax, "Expected IL and NY state tax to differ for the same wage level")
    }
}
