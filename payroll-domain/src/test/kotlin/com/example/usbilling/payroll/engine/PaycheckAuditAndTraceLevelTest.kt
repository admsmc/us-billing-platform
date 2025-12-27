package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.BaseCompensation
import com.example.usbilling.payroll.model.EmployeeSnapshot
import com.example.usbilling.payroll.model.FilingStatus
import com.example.usbilling.payroll.model.LocalDateRange
import com.example.usbilling.payroll.model.PayFrequency
import com.example.usbilling.payroll.model.PayPeriod
import com.example.usbilling.payroll.model.PaycheckInput
import com.example.usbilling.payroll.model.Percent
import com.example.usbilling.payroll.model.TaxBasis
import com.example.usbilling.payroll.model.TaxContext
import com.example.usbilling.payroll.model.TaxJurisdiction
import com.example.usbilling.payroll.model.TaxJurisdictionType
import com.example.usbilling.payroll.model.TaxRule
import com.example.usbilling.payroll.model.TimeSlice
import com.example.usbilling.payroll.model.TraceStep
import com.example.usbilling.payroll.model.YtdSnapshot
import com.example.usbilling.payroll.model.audit.TraceLevel
import com.example.usbilling.payroll.model.config.DeductionConfigRepository
import com.example.usbilling.payroll.model.config.DeductionKind
import com.example.usbilling.payroll.model.config.DeductionPlan
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillingCycleId
import com.example.usbilling.shared.BillId
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PaycheckAuditAndTraceLevelTest {

    private fun baseInput(employerId: UtilityId, employeeId: CustomerId): PaycheckInput {
        val checkDate = LocalDate.of(2025, 1, 15)
        val period = PayPeriod(
            id = "AUDIT-TEST-PP",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
            checkDate = checkDate,
            frequency = PayFrequency.BIWEEKLY,
        )
        val snapshot = EmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            homeState = "CA",
            workState = "CA",
            filingStatus = FilingStatus.SINGLE,
            baseCompensation = BaseCompensation.Salaried(
                annualSalary = Money(260_000_00L),
                frequency = period.frequency,
            ),
        )

        val federalRule = TaxRule.FlatRateTax(
            id = "EE_FLAT_10_AUDIT_TEST",
            jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
            basis = TaxBasis.FederalTaxable,
            rate = Percent(0.10),
        )

        return PaycheckInput(
            paycheckId = BillId("chk-audit-1"),
            payRunId = BillingCycleId("run-audit-1"),
            employerId = employerId,
            employeeId = employeeId,
            period = period,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = period,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = TaxContext(federal = listOf(federalRule)),
            priorYtd = YtdSnapshot(year = checkDate.year),
        )
    }

    private fun deductionRepo(planId: String): DeductionConfigRepository = object : DeductionConfigRepository {
        override fun findPlansForEmployer(employerId: UtilityId): List<DeductionPlan> = listOf(
            DeductionPlan(
                id = planId,
                name = "401k",
                kind = DeductionKind.PRETAX_RETIREMENT_EMPLOYEE,
                employeeRate = Percent(0.10),
            ),
        )
    }

    @Test
    fun `AUDIT mode produces populated stable PaycheckAudit and empty CalculationTrace`() {
        val employerId = UtilityId("EMP-AUDIT")
        val employeeId = CustomerId("EE-AUDIT")
        val input = baseInput(employerId, employeeId)
        val computedAt = Instant.parse("2025-01-15T12:00:00Z")

        val planId = "401K_AUDIT"
        val repo = deductionRepo(planId)

        val c1 = PayrollEngine.calculatePaycheckComputation(
            input = input,
            computedAt = computedAt,
            traceLevel = TraceLevel.AUDIT,
            earningConfig = null,
            deductionConfig = repo,
        )

        val c2 = PayrollEngine.calculatePaycheckComputation(
            input = input,
            computedAt = computedAt,
            traceLevel = TraceLevel.AUDIT,
            earningConfig = null,
            deductionConfig = repo,
        )

        // Deterministic for stable inputs.
        assertEquals(c1.audit, c2.audit)

        val paycheck = c1.paycheck
        assertTrue(paycheck.trace.steps.isEmpty(), "Expected empty trace steps in AUDIT mode")

        val audit = c1.audit
        assertEquals(1, audit.schemaVersion)
        assertEquals(PayrollEngine.version(), audit.engineVersion)
        assertEquals(computedAt, audit.computedAt)

        assertEquals(employerId.value, audit.employerId)
        assertEquals(employeeId.value, audit.employeeId)
        assertEquals(input.paycheckId.value, audit.paycheckId)
        assertEquals(input.payRunId?.value, audit.payRunId)
        assertEquals(input.period.id, audit.payPeriodId)
        assertEquals(input.period.checkDate, audit.checkDate)

        // Gross: 260k / 26 = 10,000.
        assertEquals(10_000_00L, audit.cashGrossCents)

        // 10% pretax deduction reduces FederalTaxable -> 9,000.
        assertEquals(9_000_00L, audit.federalTaxableCents)
        assertEquals(1, audit.appliedTaxRuleIds.size)
        assertTrue(audit.appliedTaxRuleIds.contains("EE_FLAT_10_AUDIT_TEST"))

        // Deductions include 401k plan id (we store plan ids as DeductionCode values).
        assertTrue(audit.appliedDeductionPlanIds.contains(planId))

        // Totals should reconcile.
        assertEquals(900_00L, audit.employeeTaxCents)
        assertEquals(0L, audit.employerTaxCents)
        assertEquals(1_000_00L, audit.preTaxDeductionCents)
        assertEquals(0L, audit.postTaxDeductionCents)
        assertEquals(0L, audit.garnishmentCents)
        assertEquals(8_100_00L, audit.netCents)

        // Paycheck output matches audit totals.
        assertEquals(audit.cashGrossCents, paycheck.gross.amount)
        assertEquals(audit.netCents, paycheck.net.amount)
    }

    @Test
    fun `DEBUG mode preserves trace while still producing audit`() {
        val input = baseInput(UtilityId("EMP-DBG"), CustomerId("EE-DBG"))
        val computedAt = Instant.parse("2025-01-15T12:00:00Z")

        val result = PayrollEngine.calculatePaycheckComputation(
            input = input,
            computedAt = computedAt,
            traceLevel = TraceLevel.DEBUG,
            earningConfig = null,
            deductionConfig = null,
        )

        assertNotNull(result.audit)
        assertTrue(result.paycheck.trace.steps.isNotEmpty(), "Expected non-empty trace steps in DEBUG mode")

        val basis = result.paycheck.trace.steps.filterIsInstance<TraceStep.BasisComputed>()
            .firstOrNull { it.basis == TaxBasis.Gross }
        assertNotNull(basis, "Expected BasisComputed(Gross) trace in DEBUG mode")
    }
}
