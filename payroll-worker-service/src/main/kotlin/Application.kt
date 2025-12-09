package com.example.uspayroll.worker

import com.example.uspayroll.payroll.engine.PayrollEngine
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.tax.api.TaxContextProvider
import com.example.uspayroll.tax.service.FederalWithholdingCalculator
import com.example.uspayroll.tax.service.FederalWithholdingInput
import com.example.uspayroll.labor.impl.LaborStandardsContextProvider
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@SpringBootApplication
class WorkerApplication

fun main(args: Array<String>) {
    runApplication<WorkerApplication>(*args)
}

/**
 * Service responsible for orchestrating a payroll run for an employer.
 *
 * This service demonstrates the intended performance behavior:
 * - It calls [TaxContextProvider] once per (employer, checkDate).
 * - It reuses the resulting [TaxContext] for every employee in the run.
 */
@org.springframework.stereotype.Service
class PayrollRunService(
    private val taxContextProvider: TaxContextProvider,
    private val earningConfigRepository: com.example.uspayroll.payroll.model.config.EarningConfigRepository,
    private val deductionConfigRepository: com.example.uspayroll.payroll.model.config.DeductionConfigRepository,
    private val federalWithholdingCalculator: FederalWithholdingCalculator,
    private val laborStandardsContextProvider: LaborStandardsContextProvider,
) {

    fun runDemoPayForEmployer(): List<Pair<PaycheckResult, Money>> {
        val employerId = EmployerId("emp-1")
        val checkDate = LocalDate.of(2025, 1, 15)

        // Load tax context ONCE for this employer/date.
        val taxContext = taxContextProvider.getTaxContext(employerId, checkDate)
        val laborStandardsByEmployee = mutableMapOf<EmployeeId, LaborStandardsContext?>()

        val period = PayPeriod(
            id = "2025-01-BW1",
            employerId = employerId,
            dateRange = LocalDateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 14)),
            checkDate = checkDate,
            frequency = PayFrequency.BIWEEKLY,
        )

        // In a real system this would come from HR; here we just model two
        // employees to demonstrate reuse of the same TaxContext.
        val employees = listOf(
            EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId("ee-1"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(260_000_00L),
                    frequency = period.frequency,
                ),
            ),
            EmployeeSnapshot(
                employerId = employerId,
                employeeId = EmployeeId("ee-2"),
                homeState = "CA",
                workState = "CA",
                filingStatus = FilingStatus.SINGLE,
                baseCompensation = BaseCompensation.Salaried(
                    annualSalary = Money(130_000_00L),
                    frequency = period.frequency,
                ),
            ),
        )

        return employees.mapIndexed { index, snapshot ->
            val laborStandards = laborStandardsByEmployee.getOrPut(snapshot.employeeId) {
                laborStandardsContextProvider.getLaborStandards(
                    employerId = employerId,
                    asOfDate = checkDate,
                    workState = snapshot.workState,
                    homeState = snapshot.homeState,
                )
            }
            val input = PaycheckInput(
                paycheckId = com.example.uspayroll.shared.PaycheckId("chk-demo-${index + 1}"),
                payRunId = PayRunId("run-demo"),
                employerId = employerId,
                employeeId = snapshot.employeeId,
                period = period,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = period,
                    regularHours = 0.0,
                    overtimeHours = 0.0,
                ),
                taxContext = taxContext,
                priorYtd = YtdSnapshot(year = checkDate.year),
                laborStandards = laborStandards,
            )

            val paycheck = PayrollEngine.calculatePaycheck(
                input = input,
                earningConfig = earningConfigRepository,
                deductionConfig = deductionConfigRepository,
            )

            val federalWithholding = federalWithholdingCalculator.computeWithholding(
                FederalWithholdingInput(paycheckInput = input),
            )

            paycheck to federalWithholding
        }
    }
}

/**
 * Simple HTTP endpoint to trigger the demo payroll run and show the reused
 * TaxContext behavior end-to-end.
 */
@RestController
class PayrollRunController(
    private val payrollRunService: PayrollRunService,
) {

    @GetMapping("/dry-run-paychecks")
    fun dryRunPaychecks(): Map<String, Any> {
        val results = payrollRunService.runDemoPayForEmployer()
        return mapOf(
            "version" to PayrollEngine.version(),
            "paychecks" to results.map { (paycheck, federalWithholding) ->
                mapOf(
                    "paycheckId" to paycheck.paycheckId.value,
                    "employeeId" to paycheck.employeeId.value,
                    "grossCents" to paycheck.gross.amount,
                    "netCents" to paycheck.net.amount,
                    "federalWithholdingCents_calc" to federalWithholding.amount,
                )
            },
        )
    }
}
