package com.example.uspayroll.worker

import com.example.uspayroll.labor.api.LaborStandardsContextProvider
import com.example.uspayroll.payroll.engine.PayrollEngine
import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.payroll.model.audit.TraceLevel
import com.example.uspayroll.payroll.model.garnishment.GarnishmentContext
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.toLocalityCodeStrings
import com.example.uspayroll.tax.api.TaxContextProvider
import com.example.uspayroll.tax.service.FederalWithholdingCalculator
import com.example.uspayroll.tax.service.FederalWithholdingInput
import com.example.uspayroll.worker.config.WorkerPayrollProperties
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
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
    private val localityResolver: LocalityResolver,
    private val hrClient: com.example.uspayroll.hr.client.HrClient? = null,
    private val taxClient: com.example.uspayroll.worker.client.TaxClient? = null,
    private val laborClient: com.example.uspayroll.worker.client.LaborStandardsClient? = null,
    private val timeClient: com.example.uspayroll.worker.client.TimeClient? = null,
    private val meterRegistry: MeterRegistry,
    private val garnishmentEngineProperties: GarnishmentEngineProperties,
    private val payrollProperties: WorkerPayrollProperties,
) {

    private val logger = LoggerFactory.getLogger(PayrollRunService::class.java)

    fun runDemoPayForEmployer(): List<Pair<PaycheckResult, Money>> {
        val employerId = EmployerId("emp-1")
        val checkDate = LocalDate.of(2025, 1, 15)

        // Load tax context ONCE for this employer/date. For the demo path we
        // currently do not vary locality by employee or city; a future
        // iteration can plug LocalityResolver in here once work cities are
        // modeled for the demo employees.
        val taxContext = taxClient?.getTaxContext(employerId, checkDate)
            ?: taxContextProvider.getTaxContext(employerId, checkDate)
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

            val paycheck = PayrollEngine.calculatePaycheckComputation(
                input = input,
                computedAt = Instant.now(),
                traceLevel = payrollProperties.traceLevel,
                earningConfig = earningConfigRepository,
                deductionConfig = deductionConfigRepository,
            ).paycheck

            val federalWithholding = federalWithholdingCalculator.computeWithholding(
                FederalWithholdingInput(paycheckInput = input),
            )

            paycheck to federalWithholding
        }
    }

    /**
     * HR-backed path that uses HrClient to obtain the pay period and employee
     * snapshots over HTTP, then runs the payroll engine. This is intended for
     * integration tests and future real flows.
     */
    fun runHrBackedPayForPeriod(
        employerId: EmployerId,
        payPeriodId: String,
        employeeIds: List<EmployeeId>,
        /** Optional unique suffix for paycheck IDs so repeated runs don't collide in HR withholding events. */
        runId: String? = null,
    ): List<PaycheckResult> {
        return employeeIds.map { eid ->
            computeHrBackedPaycheck(
                employerId = employerId,
                payPeriodId = payPeriodId,
                employeeId = eid,
                runId = runId,
                recordHrWithholdings = true,
            )
        }
    }

    /**
     * Benchmark/debug helper: compute a single HR-backed paycheck but do NOT record
     * garnishment withholding events back to HR.
     */
    fun previewHrBackedPaycheck(
        employerId: EmployerId,
        payPeriodId: String,
        employeeId: EmployeeId,
    ): PaycheckResult = computeHrBackedPaycheck(
        employerId = employerId,
        payPeriodId = payPeriodId,
        employeeId = employeeId,
        runId = "preview",
        recordHrWithholdings = false,
    )

    private fun computeHrBackedPaycheck(
        employerId: EmployerId,
        payPeriodId: String,
        employeeId: EmployeeId,
        runId: String?,
        recordHrWithholdings: Boolean,
    ): PaycheckResult {
        val client = requireNotNull(hrClient) { "HrClient is required for HR-backed flows" }

        val payPeriod = client.getPayPeriod(employerId, payPeriodId)
            ?: error("No pay period '$payPeriodId' for employer ${employerId.value}")

        // For HR-backed flows, derive locality codes from the employee's
        // work state and city when fetching the TaxContext. For now, we
        // resolve per employee below and do not reuse a single TaxContext
        // across employees when localities may differ.

        val eid = employeeId
        val snapshot = client.getEmployeeSnapshot(
            employerId = employerId,
            employeeId = eid,
            asOfDate = payPeriod.checkDate,
        ) ?: error("No employee snapshot for ${eid.value} as of ${payPeriod.checkDate}")

            val localityCodes = localityResolver.resolve(
                workState = snapshot.workState,
                workCity = snapshot.workCity,
            )

            val taxContext = taxClient?.getTaxContext(
                employerId = employerId,
                asOfDate = payPeriod.checkDate,
                residentState = snapshot.homeState,
                workState = snapshot.workState,
                localityCodes = localityCodes.toLocalityCodeStrings(),
            ) ?: taxContextProvider.getTaxContext(
                employerId = employerId,
                asOfDate = payPeriod.checkDate,
                // In-process provider will need an overload in a later step if
                // we want it to honor locality codes directly.
            )

            val laborStandards = laborClient?.getLaborStandards(
                employerId = employerId,
                asOfDate = payPeriod.checkDate,
                workState = snapshot.workState,
                homeState = snapshot.homeState,
                localityCodes = localityCodes.toLocalityCodeStrings(),
            ) ?: laborStandardsContextProvider.getLaborStandards(
                employerId = employerId,
                asOfDate = payPeriod.checkDate,
                workState = snapshot.workState,
                homeState = snapshot.homeState,
            )

            val garnishmentOrders = client.getGarnishmentOrders(
                employerId = employerId,
                employeeId = eid,
                asOfDate = payPeriod.checkDate,
            )
            if (garnishmentOrders.isNotEmpty()) {
                logger.info(
                    "payroll_run.garnishments.fetched orders={} employer={} employee={} pay_period={} check_date={}",
                    garnishmentOrders.size,
                    employerId.value,
                    eid.value,
                    payPeriod.id,
                    payPeriod.checkDate,
                )
                meterRegistry.counter(
                    "payroll.garnishments.employees_with_orders",
                    "employer_id",
                    employerId.value,
                ).increment()
            }

            val engineEnabled = garnishmentEngineProperties.isEnabledFor(employerId)
            val effectiveOrders = if (engineEnabled) garnishmentOrders else emptyList()
            val garnishmentContext = GarnishmentContext(orders = effectiveOrders)

            // Construct a SupportCapContext for employees with support orders,
            // using support-specific metadata surfaced from HR where present.
            val supportCapContext = SupportProfiles.forEmployee(
                homeState = snapshot.homeState,
                orders = effectiveOrders,
            )

            val paycheckId = if (runId.isNullOrBlank()) {
                com.example.uspayroll.shared.PaycheckId("chk-${payPeriod.id}-${eid.value}")
            } else {
                com.example.uspayroll.shared.PaycheckId("chk-${payPeriod.id}-${eid.value}-$runId")
            }

            val payRunId = if (runId.isNullOrBlank()) {
                PayRunId("run-${payPeriod.id}")
            } else {
                PayRunId("run-${payPeriod.id}-$runId")
            }

            val baseComp = snapshot.baseCompensation

            val timeSummary = if (baseComp is BaseCompensation.Hourly && timeClient != null) {
                timeClient.getTimeSummary(
                    employerId = employerId,
                    employeeId = eid,
                    start = payPeriod.dateRange.startInclusive,
                    end = payPeriod.dateRange.endInclusive,
                    workState = snapshot.workState,
                )
            } else {
                null
            }

            val dtEarnings: List<EarningInput> = if (baseComp is BaseCompensation.Hourly && timeSummary != null && timeSummary.doubleTimeHours > 0.0) {
                val dtRateCents = (baseComp.hourlyRate.amount * 2.0).toLong()
                listOf(
                    EarningInput(
                        code = EarningCode("HOURLY_DT"),
                        units = timeSummary.doubleTimeHours,
                        rate = Money(dtRateCents),
                        amount = null,
                    ),
                )
            } else {
                emptyList()
            }

            // Tip earnings (time-derived): for tipped hourly employees, include explicit tips
            // as additional earnings using totals returned by time-ingestion-service.
            //
            // We keep separate codes for cash/charged/allocated so the output looks more like real payroll,
            // but all are categorized as EarningCategory.TIPS by earning config.
            val tipEarnings: List<EarningInput> = if (baseComp is BaseCompensation.Hourly && snapshot.isTippedEmployee && timeSummary != null) {
                buildList {
                    if (timeSummary.cashTipsCents > 0L) {
                        add(
                            EarningInput(
                                code = EarningCode("TIPS_CASH"),
                                units = 1.0,
                                rate = null,
                                amount = Money(timeSummary.cashTipsCents),
                            ),
                        )
                    }
                    if (timeSummary.chargedTipsCents > 0L) {
                        add(
                            EarningInput(
                                code = EarningCode("TIPS_CHARGED"),
                                units = 1.0,
                                rate = null,
                                amount = Money(timeSummary.chargedTipsCents),
                            ),
                        )
                    }
                    if (timeSummary.allocatedTipsCents > 0L) {
                        add(
                            EarningInput(
                                code = EarningCode("TIPS_ALLOCATED"),
                                units = 1.0,
                                rate = null,
                                amount = Money(timeSummary.allocatedTipsCents),
                            ),
                        )
                    }
                }
            } else {
                emptyList()
            }

            val otherEarnings: List<EarningInput> = dtEarnings + tipEarnings + run {
                if (baseComp is BaseCompensation.Hourly && timeSummary != null) {
                    buildList {
                        if (timeSummary.commissionCents > 0L) {
                            add(
                                EarningInput(
                                    code = EarningCode("COMMISSION"),
                                    units = 1.0,
                                    rate = null,
                                    amount = Money(timeSummary.commissionCents),
                                ),
                            )
                        }
                        if (timeSummary.bonusCents > 0L) {
                            add(
                                EarningInput(
                                    code = EarningCode("BONUS"),
                                    units = 1.0,
                                    rate = null,
                                    amount = Money(timeSummary.bonusCents),
                                ),
                            )
                        }
                        if (timeSummary.reimbursementNonTaxableCents > 0L) {
                            add(
                                EarningInput(
                                    code = EarningCode("EXP_REIMB"),
                                    units = 1.0,
                                    rate = null,
                                    amount = Money(timeSummary.reimbursementNonTaxableCents),
                                ),
                            )
                        }
                    }
                } else {
                    emptyList()
                }
            }

            val input = PaycheckInput(
                paycheckId = paycheckId,
                payRunId = payRunId,
                employerId = employerId,
                employeeId = snapshot.employeeId,
                period = payPeriod,
                employeeSnapshot = snapshot,
                timeSlice = TimeSlice(
                    period = payPeriod,
                    regularHours = timeSummary?.regularHours ?: 0.0,
                    overtimeHours = timeSummary?.overtimeHours ?: 0.0,
                    otherEarnings = otherEarnings,
                ),
                taxContext = taxContext,
                priorYtd = YtdSnapshot(year = payPeriod.checkDate.year),
                laborStandards = laborStandards,
                garnishments = garnishmentContext,
            )

            val paycheck = PayrollEngine.calculatePaycheckComputation(
                input = input,
                computedAt = Instant.now(),
                traceLevel = payrollProperties.traceLevel,
                earningConfig = earningConfigRepository,
                deductionConfig = deductionConfigRepository,
                supportCapContext = supportCapContext,
            ).paycheck

            // Emit metrics for protected-earnings adjustments, if any.
            if (payrollProperties.traceLevel == TraceLevel.DEBUG) {
                val protectedSteps = paycheck.trace.steps.filterIsInstance<TraceStep.ProtectedEarningsApplied>()
                if (protectedSteps.isNotEmpty()) {
                    meterRegistry.counter(
                        "payroll.garnishments.protected_floor_applied",
                        "employer_id",
                        employerId.value,
                    ).increment(protectedSteps.size.toDouble())
                }
            }

            // Record garnishment withholdings back to HR for this paycheck.
            if (recordHrWithholdings && effectiveOrders.isNotEmpty()) {
                val deductionsByCode = paycheck.deductions.associateBy { it.code.value }
                val events = effectiveOrders.map { order ->
                    val line = deductionsByCode[order.orderId.value]
                    val withheld = line?.amount ?: Money(0L)
                    com.example.uspayroll.hr.http.GarnishmentWithholdingEvent(
                        orderId = order.orderId.value,
                        paycheckId = input.paycheckId.value,
                        payRunId = input.payRunId?.value,
                        checkDate = payPeriod.checkDate,
                        withheld = withheld,
                        netPay = paycheck.net,
                    )
                }

                logger.info(
                    "payroll_run.garnishments.withholdings_sent events={} employer={} employee={} pay_period={} check_date={} total_withheld_cents={} orders_with_positive_withholding={}",
                    events.size,
                    employerId.value,
                    eid.value,
                    payPeriod.id,
                    payPeriod.checkDate,
                    events.sumOf { it.withheld.amount },
                    events.count { it.withheld.amount > 0L },
                )

                client.recordGarnishmentWithholding(
                    employerId = employerId,
                    employeeId = eid,
                    request = com.example.uspayroll.hr.http.GarnishmentWithholdingRequest(events = events),
                )
            }

            return paycheck
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
