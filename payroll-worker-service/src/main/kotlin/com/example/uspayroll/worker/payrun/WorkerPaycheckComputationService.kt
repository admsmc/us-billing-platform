package com.example.uspayroll.worker.payrun

import com.example.uspayroll.hr.client.HrClient
import com.example.uspayroll.messaging.jobs.FinalizePayRunEmployeeJob
import com.example.uspayroll.payroll.engine.PayrollEngine
import com.example.uspayroll.payroll.model.BaseCompensation
import com.example.uspayroll.payroll.model.EarningCode
import com.example.uspayroll.payroll.model.EarningInput
import com.example.uspayroll.payroll.model.PaycheckInput
import com.example.uspayroll.payroll.model.TimeSlice
import com.example.uspayroll.payroll.model.YtdSnapshot
import com.example.uspayroll.payroll.model.audit.PaycheckComputation
import com.example.uspayroll.payroll.model.audit.TraceLevel
import com.example.uspayroll.payroll.model.config.DeductionConfigRepository
import com.example.uspayroll.payroll.model.config.EarningConfigRepository
import com.example.uspayroll.payroll.model.garnishment.GarnishmentContext
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import com.example.uspayroll.shared.toLocalityCodeStrings
import com.example.uspayroll.worker.LocalityResolver
import com.example.uspayroll.worker.SupportProfiles
import com.example.uspayroll.worker.audit.InputFingerprinter
import com.example.uspayroll.worker.client.LaborStandardsClient
import com.example.uspayroll.worker.client.TaxClient
import com.example.uspayroll.worker.client.TimeClient
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class WorkerPaycheckComputationService(
    private val hrClient: HrClient,
    private val taxClient: TaxClient,
    private val laborStandardsClient: LaborStandardsClient,
    private val timeClient: TimeClient?,
    private val localityResolver: LocalityResolver,
    private val earningConfigRepository: EarningConfigRepository,
    private val deductionConfigRepository: DeductionConfigRepository,
    private val inputFingerprinter: InputFingerprinter,
) {

    fun computeForFinalizeJob(job: FinalizePayRunEmployeeJob): PaycheckComputation {
        val employerId = EmployerId(job.employerId)
        val employeeId = EmployeeId(job.employeeId)

        val payPeriod = hrClient.getPayPeriod(employerId, job.payPeriodId)
            ?: error("No pay period '${job.payPeriodId}' for employer ${job.employerId}")

        val snapshot = hrClient.getEmployeeSnapshot(
            employerId = employerId,
            employeeId = employeeId,
            asOfDate = payPeriod.checkDate,
        ) ?: error("No employee snapshot for ${job.employeeId} as of ${payPeriod.checkDate}")

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

        val includeBaseEarnings = job.runType != "OFF_CYCLE"

        val earningOverrides: List<EarningInput> = job.earningOverrides.map { o ->
            EarningInput(
                code = EarningCode(o.code),
                units = o.units,
                rate = o.rateCents?.let { Money(it) },
                amount = o.amountCents?.let { Money(it) },
            )
        }

        val baseComp = snapshot.baseCompensation
        val hourlyBaseComp = baseComp as? BaseCompensation.Hourly

        val timeSummary: TimeClient.TimeSummary? = if (includeBaseEarnings && hourlyBaseComp != null && timeClient != null) {
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

        val shouldIncludeDoubleTime = includeBaseEarnings &&
            hourlyBaseComp != null &&
            (timeSummary?.doubleTimeHours ?: 0.0) > 0.0

        val doubleTimeEarnings: List<EarningInput> = if (shouldIncludeDoubleTime) {
            val dtRateCents = (hourlyBaseComp.hourlyRate.amount * 2.0).toLong()
            listOf(
                EarningInput(
                    code = EarningCode("HOURLY_DT"),
                    units = timeSummary!!.doubleTimeHours,
                    rate = Money(dtRateCents),
                    amount = null,
                ),
            )
        } else {
            emptyList()
        }

        val otherEarnings = earningOverrides + doubleTimeEarnings

        val input = PaycheckInput(
            paycheckId = PaycheckId(job.paycheckId),
            payRunId = PayRunId(job.payRunId),
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
