package com.example.uspayroll.orchestrator.payrun

import com.example.uspayroll.hr.client.HrClient
import com.example.uspayroll.orchestrator.audit.InputFingerprinter
import com.example.uspayroll.orchestrator.client.LaborStandardsClient
import com.example.uspayroll.orchestrator.client.TaxClient
import com.example.uspayroll.orchestrator.garnishment.SupportProfiles
import com.example.uspayroll.orchestrator.location.LocalityResolver
import com.example.uspayroll.orchestrator.persistence.PaycheckAuditStoreRepository
import com.example.uspayroll.orchestrator.persistence.PaycheckStoreRepository
import com.example.uspayroll.payroll.engine.PayrollEngine
import com.example.uspayroll.payroll.model.EarningInput
import com.example.uspayroll.payroll.model.PaycheckInput
import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.payroll.model.TimeSlice
import com.example.uspayroll.payroll.model.YtdSnapshot
import com.example.uspayroll.payroll.model.audit.PaycheckComputation
import com.example.uspayroll.payroll.model.audit.TraceLevel
import com.example.uspayroll.payroll.model.config.DeductionConfigRepository
import com.example.uspayroll.payroll.model.config.EarningConfigRepository
import com.example.uspayroll.payroll.model.garnishment.GarnishmentContext
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import com.example.uspayroll.shared.toLocalityCodeStrings
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class PaycheckComputationService(
    private val hrClient: HrClient,
    private val taxClient: TaxClient,
    private val laborStandardsClient: LaborStandardsClient,
    private val timeClient: com.example.uspayroll.orchestrator.client.TimeClient,
    private val localityResolver: LocalityResolver,
    private val earningConfigRepository: EarningConfigRepository,
    private val deductionConfigRepository: DeductionConfigRepository,
    private val paycheckStoreRepository: PaycheckStoreRepository,
    private val paycheckAuditStoreRepository: PaycheckAuditStoreRepository,
    objectMapper: ObjectMapper,
) {

    private val inputFingerprinter: InputFingerprinter = InputFingerprinter(objectMapper)

    /**
     * HR-backed paycheck computation used by orchestrator finalization flows.
     *
     * This method is intentionally scoped to a single employee so the caller
     * can implement durable, retryable per-employee execution.
     */
    fun computeAndPersistFinalPaycheckForEmployee(
        employerId: EmployerId,
        payRunId: String,
        payPeriodId: String,
        runType: com.example.uspayroll.orchestrator.payrun.model.PayRunType,
        runSequence: Int,
        paycheckId: String,
        employeeId: EmployeeId,
        earningOverrides: List<EarningInput> = emptyList(),
    ): PaycheckResult {
        val computation = computePaycheckComputationForEmployee(
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = payPeriodId,
            runType = runType,
            paycheckId = paycheckId,
            employeeId = employeeId,
            earningOverrides = earningOverrides,
        )

        val paycheck = computation.paycheck

        paycheckStoreRepository.insertFinalPaycheckIfAbsent(
            employerId = employerId,
            paycheckId = paycheck.paycheckId.value,
            payRunId = paycheck.payRunId?.value ?: payRunId,
            employeeId = paycheck.employeeId.value,
            payPeriodId = paycheck.period.id,
            runType = runType.name,
            runSequence = runSequence,
            checkDateIso = paycheck.period.checkDate.toString(),
            grossCents = paycheck.gross.amount,
            netCents = paycheck.net.amount,
            version = 1,
            payload = paycheck,
        )

        paycheckAuditStoreRepository.insertAuditIfAbsent(computation.audit)

        return paycheck
    }

    fun computePaycheckComputationForEmployee(
        employerId: EmployerId,
        payRunId: String,
        payPeriodId: String,
        runType: com.example.uspayroll.orchestrator.payrun.model.PayRunType,
        paycheckId: String,
        employeeId: EmployeeId,
        earningOverrides: List<EarningInput> = emptyList(),
    ): PaycheckComputation {
        val payPeriod = hrClient.getPayPeriod(employerId, payPeriodId)
            ?: error("No pay period '$payPeriodId' for employer ${employerId.value}")

        val snapshot = hrClient.getEmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            asOfDate = payPeriod.checkDate,
        ) ?: error("No employee snapshot for ${employeeId.value} as of ${payPeriod.checkDate}")

        val localityCodes = localityResolver.resolve(
            workState = snapshot.workState,
            workCity = snapshot.workCity,
        )

        val taxContext = taxClient.getTaxContext(
            employerId = employerId,
            asOfDate = payPeriod.checkDate,
            residentState = snapshot.homeState,
            workState = snapshot.workState,
            localityCodes = localityCodes.toLocalityCodeStrings(),
        )

        val laborStandards = laborStandardsClient.getLaborStandards(
            employerId = employerId,
            asOfDate = payPeriod.checkDate,
            workState = snapshot.workState,
            homeState = snapshot.homeState,
            localityCodes = localityCodes.toLocalityCodeStrings(),
        )

        val garnishmentOrders = hrClient.getGarnishmentOrders(
            employerId = employerId,
            employeeId = employeeId,
            asOfDate = payPeriod.checkDate,
        )

        val garnishmentContext = GarnishmentContext(orders = garnishmentOrders)

        val supportCapContext = SupportProfiles.forEmployee(
            homeState = snapshot.homeState,
            orders = garnishmentOrders,
        )

        val includeBaseEarnings = runType != com.example.uspayroll.orchestrator.payrun.model.PayRunType.OFF_CYCLE

        val baseComp = snapshot.baseCompensation
        val timeSummary = if (includeBaseEarnings && baseComp is com.example.uspayroll.payroll.model.BaseCompensation.Hourly) {
            timeClient.getTimeSummary(
                employerId = employerId,
                employeeId = employeeId,
                start = payPeriod.dateRange.startInclusive,
                end = payPeriod.dateRange.endInclusive,
                workState = snapshot.workState,
            )
        } else {
            null
        }

        val doubleTimeEarnings: List<EarningInput> = if (
            includeBaseEarnings &&
                baseComp is com.example.uspayroll.payroll.model.BaseCompensation.Hourly &&
                timeSummary != null &&
                timeSummary.doubleTimeHours > 0.0
        ) {
            val dtRateCents = (baseComp.hourlyRate.amount * 2.0).toLong()
            listOf(
                EarningInput(
                    code = com.example.uspayroll.payroll.model.EarningCode("HOURLY_DT"),
                    units = timeSummary.doubleTimeHours,
                    rate = com.example.uspayroll.shared.Money(dtRateCents),
                    amount = null,
                ),
            )
        } else {
            emptyList()
        }

        val otherEarnings = earningOverrides + doubleTimeEarnings

        val input = PaycheckInput(
            paycheckId = PaycheckId(paycheckId),
            payRunId = PayRunId(payRunId),
            employerId = employerId,
            employeeId = snapshot.employeeId,
            period = payPeriod,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = payPeriod,
                regularHours = timeSummary?.regularHours ?: 0.0,
                overtimeHours = timeSummary?.overtimeHours ?: 0.0,
                otherEarnings = otherEarnings,
                includeBaseEarnings = includeBaseEarnings,
            ),
            taxContext = taxContext,
            priorYtd = YtdSnapshot(year = payPeriod.checkDate.year),
            laborStandards = laborStandards,
            garnishments = garnishmentContext,
        )

        val computation = PayrollEngine.calculatePaycheckComputation(
            input = input,
            computedAt = Instant.now(),
            traceLevel = TraceLevel.AUDIT,
            earningConfig = earningConfigRepository,
            deductionConfig = deductionConfigRepository,
            supportCapContext = supportCapContext,
        )

        val fingerprinted = inputFingerprinter.stamp(
            audit = computation.audit,
            employerId = employerId,
            employeeSnapshot = snapshot,
            taxContext = taxContext,
            laborStandards = laborStandards,
            earningOverrides = earningOverrides,
            earningConfigRepository = earningConfigRepository,
            deductionConfigRepository = deductionConfigRepository,
        )

        return computation.copy(audit = fingerprinted)
    }
}
