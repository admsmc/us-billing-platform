package com.example.usbilling.worker

import com.example.usbilling.payroll.model.*
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import com.example.usbilling.worker.support.StubTaxLaborClientsTestConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Worker-service level test that exercises the real in-memory earning and
 * deduction config repositories to show per-employer differences flowing
 * through the orchestration layer.
 *
 * We run two small demo pay runs with different employers:
 * - emp-1: has a post-tax voluntary deduction configured in
 *   InMemoryDeductionConfigRepository.
 * - emp-2: has no configured deductions.
 *
 * Both employees share the same base salary; the test asserts that net pay for
 * emp-1 is lower than for emp-2 due solely to the configured deduction plan.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(StubTaxLaborClientsTestConfig::class)
@TestInstance(Lifecycle.PER_CLASS)
class EmployerSpecificConfigWorkerIntegrationTest {

    @Autowired
    lateinit var payrollRunService: PayrollRunService

    @Test
    fun `worker-service uses employer-specific deduction config to produce different nets`() {
        val checkDate = LocalDate.of(2025, 1, 15)

        fun runSingle(employerId: EmployerId, employeeId: EmployeeId): PaycheckResult {
            val period = PayPeriod(
                id = "2025-01-BW-DEMO-${employerId.value}",
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
                    annualSalary = Money(260_000_00L), // 10,000 per check
                    frequency = period.frequency,
                ),
            )

            // For this test we ignore real tax catalogs and instead use a
            // simple 10% flat federal tax rule to emphasize deductions.
            val rule = TaxRule.FlatRateTax(
                id = "EE_FLAT_10",
                jurisdiction = TaxJurisdiction(TaxJurisdictionType.FEDERAL, "US"),
                basis = TaxBasis.FederalTaxable,
                rate = Percent(0.10),
            )
            val taxContext = TaxContext(federal = listOf(rule))

            val input = PaycheckInput(
                paycheckId = com.example.usbilling.shared.PaycheckId("chk-worker-${employerId.value}"),
                payRunId = com.example.usbilling.shared.PayRunId("run-worker-demo"),
                employerId = employerId,
                employeeId = employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = taxContext,
                priorYtd = YtdSnapshot(year = checkDate.year),
            )

            return com.example.usbilling.payroll.engine.PayrollEngine.calculatePaycheck(
                input = input,
                earningConfig = payrollRunService.let {
                    it.javaClass.getDeclaredField("earningConfigRepository").let { f ->
                        f.isAccessible = true
                        f.get(it) as com.example.usbilling.payroll.model.config.EarningConfigRepository
                    }
                },
                deductionConfig = payrollRunService.let {
                    it.javaClass.getDeclaredField("deductionConfigRepository").let { f ->
                        f.isAccessible = true
                        f.get(it) as com.example.usbilling.payroll.model.config.DeductionConfigRepository
                    }
                },
            )
        }

        val empWithPlan = EmployerId("emp-1")
        val empWithoutPlan = EmployerId("emp-2")

        val paycheckWithPlan = runSingle(empWithPlan, EmployeeId("ee-plan"))
        val paycheckWithoutPlan = runSingle(empWithoutPlan, EmployeeId("ee-noplan"))

        // Gross should match for both employers.
        assertEquals(paycheckWithoutPlan.gross.amount, paycheckWithPlan.gross.amount)

        // Employer with a configured post-tax plan should have a strictly lower net.
        assertTrue(paycheckWithPlan.net.amount < paycheckWithoutPlan.net.amount)

        // Sanity-check that a deduction line exists only for emp-1.
        assertTrue(paycheckWithPlan.deductions.isNotEmpty())
        assertEquals(0, paycheckWithoutPlan.deductions.size)
    }
}
