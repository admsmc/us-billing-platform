package com.example.usbilling.benchmarks

import com.example.usbilling.payroll.engine.OvertimePolicy
import com.example.usbilling.payroll.engine.PayrollEngine
import com.example.usbilling.payroll.model.BaseCompensation
import com.example.usbilling.payroll.model.EarningCategory
import com.example.usbilling.payroll.model.EarningCode
import com.example.usbilling.payroll.model.EmployeeSnapshot
import com.example.usbilling.payroll.model.FilingStatus
import com.example.usbilling.payroll.model.LocalDateRange
import com.example.usbilling.payroll.model.PayFrequency
import com.example.usbilling.payroll.model.PayPeriod
import com.example.usbilling.payroll.model.PaycheckInput
import com.example.usbilling.payroll.model.Percent
import com.example.usbilling.payroll.model.TaxContext
import com.example.usbilling.payroll.model.TimeSlice
import com.example.usbilling.payroll.model.YtdSnapshot
import com.example.usbilling.payroll.model.audit.TraceLevel
import com.example.usbilling.payroll.model.config.DeductionConfigRepository
import com.example.usbilling.payroll.model.config.DeductionKind
import com.example.usbilling.payroll.model.config.DeductionPlan
import com.example.usbilling.payroll.model.config.EarningConfigRepository
import com.example.usbilling.payroll.model.config.EarningDefinition
import com.example.usbilling.payroll.model.garnishment.GarnishmentContext
import com.example.usbilling.payroll.model.garnishment.GarnishmentFormula
import com.example.usbilling.payroll.model.garnishment.GarnishmentOrder
import com.example.usbilling.payroll.model.garnishment.GarnishmentOrderId
import com.example.usbilling.payroll.model.garnishment.GarnishmentType
import com.example.usbilling.payroll.model.garnishment.ProtectedEarningsRule
import com.example.usbilling.shared.EmployeeId
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.PayRunId
import com.example.usbilling.shared.PaycheckId
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
open class PaycheckComputationBenchmark {

    @State(Scope.Thread)
    open class ScenarioState {
        lateinit var baseline: PaycheckInput
        lateinit var withVoluntaryDeduction: PaycheckInput
        lateinit var withGarnishmentAndFloor: PaycheckInput

        val earningConfig: EarningConfigRepository = object : EarningConfigRepository {
            override fun findByEmployerAndCode(employerId: EmployerId, code: EarningCode): EarningDefinition? = when (code.value) {
                "HOURLY" -> EarningDefinition(
                    code = code,
                    displayName = "Hourly Wages",
                    category = EarningCategory.REGULAR,
                    defaultRate = Money(50_00L),
                    overtimeMultiplier = 1.5,
                )
                else -> null
            }
        }

        val noDeductions: DeductionConfigRepository = object : DeductionConfigRepository {
            override fun findPlansForEmployer(employerId: EmployerId): List<DeductionPlan> = emptyList()
        }

        val voluntaryDeduction: DeductionConfigRepository = object : DeductionConfigRepository {
            override fun findPlansForEmployer(employerId: EmployerId): List<DeductionPlan> = listOf(
                DeductionPlan(
                    id = "PLAN_VOLUNTARY",
                    name = "Voluntary Post-Tax Deduction",
                    kind = DeductionKind.POSTTAX_VOLUNTARY,
                    employeeFlat = Money(100_00L),
                ),
            )
        }

        @Setup(Level.Trial)
        fun setup() {
            val employerId = EmployerId("emp-bench")
            val employeeId = EmployeeId("ee-bench")

            val period = PayPeriod(
                id = "2025-01-BW1",
                employerId = employerId,
                dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
                checkDate = LocalDate.of(2025, 1, 15),
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

            baseline = PaycheckInput(
                paycheckId = PaycheckId("chk-bench-1"),
                payRunId = PayRunId("run-bench"),
                employerId = employerId,
                employeeId = employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = TaxContext(),
                priorYtd = YtdSnapshot(year = 2025),
            )

            withVoluntaryDeduction = baseline.copy()

            val order = GarnishmentOrder(
                orderId = GarnishmentOrderId("ORDER-BENCH-1"),
                planId = "PLAN_BENCH",
                type = GarnishmentType.CREDITOR_GARNISHMENT,
                formula = GarnishmentFormula.PercentOfDisposable(Percent(0.10)),
                protectedEarningsRule = ProtectedEarningsRule.FixedFloor(Money(3_000_00L)),
            )

            withGarnishmentAndFloor = baseline.copy(
                garnishments = GarnishmentContext(orders = listOf(order)),
            )
        }
    }

    @Benchmark
    fun salariedBaseline(s: ScenarioState) = PayrollEngine.calculatePaycheckComputation(
        input = s.baseline,
        computedAt = Instant.EPOCH,
        traceLevel = TraceLevel.NONE,
        earningConfig = s.earningConfig,
        deductionConfig = s.noDeductions,
        overtimePolicy = OvertimePolicy.Default,
        employerContributions = emptyList(),
        strictYtdYear = false,
        supportCapContext = null,
    ).paycheck

    @Benchmark
    fun salariedWithVoluntaryDeduction(s: ScenarioState) = PayrollEngine.calculatePaycheckComputation(
        input = s.withVoluntaryDeduction,
        computedAt = Instant.EPOCH,
        traceLevel = TraceLevel.NONE,
        earningConfig = s.earningConfig,
        deductionConfig = s.voluntaryDeduction,
        overtimePolicy = OvertimePolicy.Default,
        employerContributions = emptyList(),
        strictYtdYear = false,
        supportCapContext = null,
    ).paycheck

    @Benchmark
    fun salariedWithGarnishmentAndFloor(s: ScenarioState) = PayrollEngine.calculatePaycheckComputation(
        input = s.withGarnishmentAndFloor,
        computedAt = Instant.EPOCH,
        traceLevel = TraceLevel.NONE,
        earningConfig = s.earningConfig,
        deductionConfig = s.noDeductions,
        overtimePolicy = OvertimePolicy.Default,
        employerContributions = emptyList(),
        strictYtdYear = false,
        supportCapContext = null,
    ).paycheck
}
