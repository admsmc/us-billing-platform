package com.example.uspayroll.orchestrator.payrun

import com.example.uspayroll.orchestrator.client.HrClient
import com.example.uspayroll.orchestrator.client.LaborStandardsClient
import com.example.uspayroll.orchestrator.client.TaxClient
import com.example.uspayroll.orchestrator.garnishment.SupportProfiles
import com.example.uspayroll.orchestrator.location.LocalityResolver
import com.example.uspayroll.orchestrator.persistence.PaycheckStoreRepository
import com.example.uspayroll.payroll.engine.PayrollEngine
import com.example.uspayroll.payroll.model.PaycheckInput
import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.payroll.model.TimeSlice
import com.example.uspayroll.payroll.model.YtdSnapshot
import com.example.uspayroll.payroll.model.config.DeductionConfigRepository
import com.example.uspayroll.payroll.model.config.EarningConfigRepository
import com.example.uspayroll.payroll.model.garnishment.GarnishmentContext
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.PaycheckId
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.toLocalityCodeStrings
import org.springframework.stereotype.Service

@Service
class PaycheckComputationService(
    private val hrClient: HrClient,
    private val taxClient: TaxClient,
    private val laborStandardsClient: LaborStandardsClient,
    private val localityResolver: LocalityResolver,
    private val earningConfigRepository: EarningConfigRepository,
    private val deductionConfigRepository: DeductionConfigRepository,
    private val paycheckStoreRepository: PaycheckStoreRepository,
) {

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
        runType: String,
        runSequence: Int,
        paycheckId: String,
        employeeId: EmployeeId,
    ): PaycheckResult {
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

        val input = PaycheckInput(
            paycheckId = PaycheckId(paycheckId),
            payRunId = PayRunId(payRunId),
            employerId = employerId,
            employeeId = snapshot.employeeId,
            period = payPeriod,
            employeeSnapshot = snapshot,
            timeSlice = TimeSlice(
                period = payPeriod,
                regularHours = 0.0,
                overtimeHours = 0.0,
            ),
            taxContext = taxContext,
            priorYtd = YtdSnapshot(year = payPeriod.checkDate.year),
            laborStandards = laborStandards,
            garnishments = garnishmentContext,
        )

        val paycheck = PayrollEngine.calculatePaycheck(
            input = input,
            earningConfig = earningConfigRepository,
            deductionConfig = deductionConfigRepository,
            supportCapContext = supportCapContext,
        )

        paycheckStoreRepository.insertFinalPaycheckIfAbsent(
            employerId = employerId,
            paycheckId = paycheck.paycheckId.value,
            payRunId = paycheck.payRunId?.value ?: payRunId,
            employeeId = paycheck.employeeId.value,
            payPeriodId = paycheck.period.id,
            runType = runType,
            runSequence = runSequence,
            checkDateIso = paycheck.period.checkDate.toString(),
            grossCents = paycheck.gross.amount,
            netCents = paycheck.net.amount,
            version = 1,
            payload = paycheck,
        )

        return paycheck
    }
}
