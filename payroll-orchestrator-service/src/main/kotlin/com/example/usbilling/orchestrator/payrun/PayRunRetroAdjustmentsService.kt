package com.example.usbilling.orchestrator.payrun

import com.example.usbilling.orchestrator.events.PayRunOutboxEnqueuer
import com.example.usbilling.orchestrator.payrun.model.ApprovalStatus
import com.example.usbilling.orchestrator.payrun.model.PayRunStatus
import com.example.usbilling.orchestrator.payrun.model.PayRunType
import com.example.usbilling.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.usbilling.orchestrator.payrun.persistence.PayRunRepository
import com.example.usbilling.orchestrator.persistence.PaycheckAuditStoreRepository
import com.example.usbilling.orchestrator.persistence.PaycheckStoreRepository
import com.example.usbilling.payroll.model.DeductionCode
import com.example.usbilling.payroll.model.DeductionLine
import com.example.usbilling.payroll.model.EarningCategory
import com.example.usbilling.payroll.model.EarningCode
import com.example.usbilling.payroll.model.EarningLine
import com.example.usbilling.payroll.model.EmployerContributionCode
import com.example.usbilling.payroll.model.EmployerContributionLine
import com.example.usbilling.payroll.model.PaycheckResult
import com.example.usbilling.payroll.model.TaxLine
import com.example.usbilling.payroll.model.YtdSnapshot
import com.example.usbilling.payroll.model.audit.PaycheckAudit
import com.example.usbilling.shared.CustomerId
import com.example.usbilling.shared.UtilityId
import com.example.usbilling.shared.Money
import com.example.usbilling.shared.BillRunId
import com.example.usbilling.shared.BillId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class PayRunRetroAdjustmentsService(
    private val payRunRepository: PayRunRepository,
    private val payRunItemRepository: PayRunItemRepository,
    private val paycheckStoreRepository: PaycheckStoreRepository,
    private val paycheckAuditStoreRepository: PaycheckAuditStoreRepository,
    private val paycheckComputationService: PaycheckComputationService,
    private val earningOverridesCodec: PayRunEarningOverridesCodec,
    private val outboxEnqueuer: PayRunOutboxEnqueuer,
) {

    data class StartRetroAdjustmentResult(
        val employerId: String,
        val sourcePayRunId: String,
        val adjustmentPayRunId: String,
        val runSequence: Int,
        val status: PayRunStatus,
        val totalItems: Int,
        val succeeded: Int,
        val failed: Int,
        val created: Boolean,
    )

    @Transactional
    fun startRetroAdjustment(
        employerId: String,
        sourcePayRunId: String,
        requestedPayRunId: String? = null,
        runSequenceOverride: Int? = null,
        idempotencyKey: String? = null,
    ): StartRetroAdjustmentResult {
        val source = payRunRepository.findPayRun(employerId, sourcePayRunId)
            ?: notFound("source payrun not found")

        if (source.approvalStatus != ApprovalStatus.APPROVED) {
            conflict("cannot retro-adjust: source payrun not approved")
        }

        val succeededSourcePaychecks = payRunItemRepository.listSucceededPaychecks(employerId, sourcePayRunId)
        if (succeededSourcePaychecks.isEmpty()) {
            conflict("source payrun has no succeeded paychecks to adjust")
        }

        val runSequence = runSequenceOverride ?: source.runSequence
        val adjustmentPayRunId = (requestedPayRunId ?: "adj-$sourcePayRunId").ifBlank { "run-${UUID.randomUUID()}" }

        val createOrGet = payRunRepository.createOrGetPayRun(
            employerId = employerId,
            payRunId = adjustmentPayRunId,
            payPeriodId = source.payPeriodId,
            runType = PayRunType.ADJUSTMENT,
            runSequence = runSequence,
            requestedIdempotencyKey = idempotencyKey,
        )

        val adjustmentRun = createOrGet.payRun

        val acceptedLink = payRunRepository.acceptCorrectionOfBillRunId(
            employerId = employerId,
            payRunId = adjustmentRun.payRunId,
            correctionOfPayRunId = sourcePayRunId,
        )
        if (!acceptedLink) {
            conflict("adjustment payrun is linked to a different source payrun")
        }

        val employeeIds = succeededSourcePaychecks.map { (employeeId, _) -> employeeId }
        payRunItemRepository.upsertQueuedItems(
            employerId = employerId,
            payRunId = adjustmentRun.payRunId,
            employeeIds = employeeIds,
        )

        val sourcePaycheckIdByEmployeeId = succeededSourcePaychecks.toMap()
        val now = Instant.now()
        val employer = UtilityId(employerId)

        employeeIds.forEach { employeeId ->
            val originalPaycheckId = sourcePaycheckIdByEmployeeId.getValue(employeeId)

            // Per-item idempotency: if already SUCCEEDED, skip.
            val current = payRunItemRepository.findItem(employerId, adjustmentRun.payRunId, employeeId)
            if (current?.status?.name == "SUCCEEDED") {
                return@forEach
            }

            val claim = payRunItemRepository.claimItem(
                employerId = employerId,
                payRunId = adjustmentRun.payRunId,
                employeeId = employeeId,
                requeueStaleMillis = 0L,
            )

            if (claim == null) {
                return@forEach
            }
            if (!claim.claimed && claim.item.status.name != "RUNNING") {
                return@forEach
            }

            val newPaycheckId = payRunItemRepository.getOrAssignBillId(
                employerId = employerId,
                payRunId = adjustmentRun.payRunId,
                employeeId = employeeId,
            )

            @Suppress("TooGenericExceptionCaught")
            try {
                val original = paycheckStoreRepository.findPaycheck(employer, originalPaycheckId)
                    ?: error("source paycheck not found: $originalPaycheckId")

                val originalAudit = paycheckAuditStoreRepository.findAudit(employer, originalPaycheckId)
                    ?: error("source paycheck audit not found: $originalPaycheckId")

                val sourceItem = payRunItemRepository.findItem(employerId, sourcePayRunId, employeeId)
                val earningOverrides = sourceItem?.earningOverridesJson
                    ?.takeIf { it.isNotBlank() }
                    ?.let { earningOverridesCodec.decodeToEarningInputs(it) }
                    ?: emptyList()

                val corrected = paycheckComputationService.computePaycheckComputationForEmployee(
                    employerId = employer,
                    payRunId = sourcePayRunId,
                    payPeriodId = source.payPeriodId,
                    runType = source.runType,
                    paycheckId = originalPaycheckId,
                    employeeId = CustomerId(employeeId),
                    earningOverrides = earningOverrides,
                )

                val deltaPaycheck = deltaPaycheck(
                    original = original,
                    corrected = corrected.paycheck,
                    newPaycheckId = newPaycheckId,
                    newPayRunId = adjustmentRun.payRunId,
                )

                paycheckStoreRepository.insertFinalPaycheckIfAbsent(
                    employerId = employer,
                    paycheckId = deltaPaycheck.paycheckId.value,
                    payRunId = adjustmentRun.payRunId,
                    employeeId = deltaPaycheck.employeeId.value,
                    payPeriodId = deltaPaycheck.period.id,
                    runType = PayRunType.ADJUSTMENT.name,
                    runSequence = adjustmentRun.runSequence,
                    checkDateIso = deltaPaycheck.period.checkDate.toString(),
                    grossCents = deltaPaycheck.gross.amount,
                    netCents = deltaPaycheck.net.amount,
                    version = 1,
                    payload = deltaPaycheck,
                )

                paycheckStoreRepository.setCorrectionOfPaycheckIdIfNull(
                    employerId = employer,
                    paycheckId = deltaPaycheck.paycheckId.value,
                    correctionOfPaycheckId = originalPaycheckId,
                )

                val deltaAudit = deltaAudit(
                    original = originalAudit,
                    corrected = corrected.audit,
                    newPaycheckId = deltaPaycheck.paycheckId.value,
                    newPayRunId = adjustmentRun.payRunId,
                    now = now,
                )
                paycheckAuditStoreRepository.insertAuditIfAbsent(deltaAudit)

                payRunItemRepository.markSucceeded(
                    employerId = employerId,
                    payRunId = adjustmentRun.payRunId,
                    employeeId = employeeId,
                    paycheckId = deltaPaycheck.paycheckId.value,
                )
            } catch (t: Exception) {
                payRunItemRepository.markFailed(
                    employerId = employerId,
                    payRunId = adjustmentRun.payRunId,
                    employeeId = employeeId,
                    error = t.message ?: t::class.java.name,
                )
            }
        }

        val counts = payRunItemRepository.countsForPayRun(employerId, adjustmentRun.payRunId)
        val terminalStatus = computedTerminalStatus(total = counts.total, succeeded = counts.succeeded, failed = counts.failed)

        outboxEnqueuer.finalizePayRunAndEnqueueOutboxEvents(
            employerId = employerId,
            payRunId = adjustmentRun.payRunId,
            payPeriodId = source.payPeriodId,
            status = terminalStatus,
            total = counts.total,
            succeeded = counts.succeeded,
            failed = counts.failed,
        )

        return StartRetroAdjustmentResult(
            employerId = employerId,
            sourcePayRunId = sourcePayRunId,
            adjustmentPayRunId = adjustmentRun.payRunId,
            runSequence = runSequenceOrFallback(adjustmentRun.runSequence, runSequence),
            status = terminalStatus,
            totalItems = counts.total,
            succeeded = counts.succeeded,
            failed = counts.failed,
            created = createOrGet.wasCreated,
        )
    }

    private fun computedTerminalStatus(total: Int, succeeded: Int, failed: Int): PayRunStatus = when {
        total == 0 -> PayRunStatus.FAILED
        failed == 0 && succeeded == total -> PayRunStatus.FINALIZED
        succeeded > 0 && failed > 0 -> PayRunStatus.PARTIALLY_FINALIZED
        succeeded == 0 && failed == total -> PayRunStatus.FAILED
        else -> PayRunStatus.FAILED
    }

    private fun runSequenceOrFallback(actual: Int, requested: Int): Int = if (actual > 0) actual else requested

    private fun deltaPaycheck(original: PaycheckResult, corrected: PaycheckResult, newPaycheckId: String, newPayRunId: String): PaycheckResult {
        fun Money.minus(other: Money): Money = Money(amount - other.amount, currency)

        val deltaEarnings = deltaEarnings(original.earnings, corrected.earnings)
        val deltaEmployeeTaxes = deltaTaxes(original.employeeTaxes, corrected.employeeTaxes)
        val deltaEmployerTaxes = deltaTaxes(original.employerTaxes, corrected.employerTaxes)
        val deltaDeductions = deltaDeductions(original.deductions, corrected.deductions)
        val deltaEmployerContribs = deltaEmployerContributions(original.employerContributions, corrected.employerContributions)

        val deltaGross = corrected.gross.minus(original.gross)
        val deltaNet = corrected.net.minus(original.net)

        val ytdAfterDelta = deltaYtd(original.ytdAfter, corrected.ytdAfter)

        return original.copy(
            paycheckId = BillId(newPaycheckId),
            payRunId = BillRunId(newPayRunId),
            earnings = deltaEarnings,
            employeeTaxes = deltaEmployeeTaxes,
            employerTaxes = deltaEmployerTaxes,
            deductions = deltaDeductions,
            employerContributions = deltaEmployerContribs,
            gross = deltaGross,
            net = deltaNet,
            ytdAfter = ytdAfterDelta,
            trace = original.trace.copy(steps = emptyList()),
        )
    }

    private fun deltaAudit(original: PaycheckAudit, corrected: PaycheckAudit, newPaycheckId: String, newPayRunId: String, now: Instant): PaycheckAudit {
        fun diff(a: Long, b: Long): Long = b - a

        val appliedTaxRuleIds = (original.appliedTaxRuleIds + corrected.appliedTaxRuleIds).distinct()
        val appliedDeductionPlanIds = (original.appliedDeductionPlanIds + corrected.appliedDeductionPlanIds).distinct()
        val appliedGarnishmentOrderIds = (original.appliedGarnishmentOrderIds + corrected.appliedGarnishmentOrderIds).distinct()

        return corrected.copy(
            computedAt = now,
            paycheckId = newPaycheckId,
            payRunId = newPayRunId,
            appliedTaxRuleIds = appliedTaxRuleIds,
            appliedDeductionPlanIds = appliedDeductionPlanIds,
            appliedGarnishmentOrderIds = appliedGarnishmentOrderIds,
            cashGrossCents = diff(original.cashGrossCents, corrected.cashGrossCents),
            grossTaxableCents = diff(original.grossTaxableCents, corrected.grossTaxableCents),
            federalTaxableCents = diff(original.federalTaxableCents, corrected.federalTaxableCents),
            stateTaxableCents = diff(original.stateTaxableCents, corrected.stateTaxableCents),
            socialSecurityWagesCents = diff(original.socialSecurityWagesCents, corrected.socialSecurityWagesCents),
            medicareWagesCents = diff(original.medicareWagesCents, corrected.medicareWagesCents),
            supplementalWagesCents = diff(original.supplementalWagesCents, corrected.supplementalWagesCents),
            futaWagesCents = diff(original.futaWagesCents, corrected.futaWagesCents),
            employeeTaxCents = diff(original.employeeTaxCents, corrected.employeeTaxCents),
            employerTaxCents = diff(original.employerTaxCents, corrected.employerTaxCents),
            preTaxDeductionCents = diff(original.preTaxDeductionCents, corrected.preTaxDeductionCents),
            postTaxDeductionCents = diff(original.postTaxDeductionCents, corrected.postTaxDeductionCents),
            garnishmentCents = diff(original.garnishmentCents, corrected.garnishmentCents),
            netCents = diff(original.netCents, corrected.netCents),
        )
    }

    private fun deltaEarnings(original: List<EarningLine>, corrected: List<EarningLine>): List<EarningLine> {
        fun Money.minus(other: Money): Money = Money(amount - other.amount, currency)

        val byKeyOriginal = original.associateBy { it.code.value to it.category.name }
        val byKeyCorrected = corrected.associateBy { it.code.value to it.category.name }

        val keys = (byKeyOriginal.keys + byKeyCorrected.keys).sortedWith(compareBy({ it.first }, { it.second }))
        return keys.mapNotNull { key ->
            val o = byKeyOriginal[key]
            val c = byKeyCorrected[key]
            val amount = (c?.amount ?: Money(0L)).minus(o?.amount ?: Money(0L))
            if (amount.amount == 0L) return@mapNotNull null

            val code = EarningCode(key.first)
            val category = c?.category ?: o?.category ?: EarningCategory.REGULAR
            EarningLine(
                code = code,
                category = category,
                description = "Retro delta ${code.value}",
                units = 0.0,
                rate = null,
                amount = amount,
            )
        }
    }

    private fun deltaTaxes(original: List<TaxLine>, corrected: List<TaxLine>): List<TaxLine> {
        fun Money.minus(other: Money): Money = Money(amount - other.amount, currency)

        val byRuleOriginal = original.associateBy { it.ruleId }
        val byRuleCorrected = corrected.associateBy { it.ruleId }

        val keys = (byRuleOriginal.keys + byRuleCorrected.keys).sorted()
        return keys.mapNotNull { ruleId ->
            val o = byRuleOriginal[ruleId]
            val c = byRuleCorrected[ruleId]

            val basis = (c?.basis ?: Money(0L)).minus(o?.basis ?: Money(0L))
            val amount = (c?.amount ?: Money(0L)).minus(o?.amount ?: Money(0L))
            if (amount.amount == 0L && basis.amount == 0L) return@mapNotNull null

            val ref = c ?: o ?: return@mapNotNull null
            ref.copy(
                description = "Retro delta ${ref.jurisdiction.code}",
                basis = basis,
                amount = amount,
            )
        }
    }

    private fun deltaDeductions(original: List<DeductionLine>, corrected: List<DeductionLine>): List<DeductionLine> {
        fun Money.minus(other: Money): Money = Money(amount - other.amount, currency)

        val byCodeOriginal = original.associateBy { it.code.value }
        val byCodeCorrected = corrected.associateBy { it.code.value }

        val keys = (byCodeOriginal.keys + byCodeCorrected.keys).sorted()
        return keys.mapNotNull { codeValue ->
            val o = byCodeOriginal[codeValue]
            val c = byCodeCorrected[codeValue]
            val amount = (c?.amount ?: Money(0L)).minus(o?.amount ?: Money(0L))
            if (amount.amount == 0L) return@mapNotNull null

            DeductionLine(
                code = DeductionCode(codeValue),
                description = "Retro delta $codeValue",
                amount = amount,
            )
        }
    }

    private fun deltaEmployerContributions(original: List<EmployerContributionLine>, corrected: List<EmployerContributionLine>): List<EmployerContributionLine> {
        fun Money.minus(other: Money): Money = Money(amount - other.amount, currency)

        val byCodeOriginal = original.associateBy { it.code.value }
        val byCodeCorrected = corrected.associateBy { it.code.value }

        val keys = (byCodeOriginal.keys + byCodeCorrected.keys).sorted()
        return keys.mapNotNull { codeValue ->
            val o = byCodeOriginal[codeValue]
            val c = byCodeCorrected[codeValue]
            val amount = (c?.amount ?: Money(0L)).minus(o?.amount ?: Money(0L))
            if (amount.amount == 0L) return@mapNotNull null

            EmployerContributionLine(
                code = EmployerContributionCode(codeValue),
                description = "Retro delta $codeValue",
                amount = amount,
            )
        }
    }

    private fun deltaYtd(original: YtdSnapshot, corrected: YtdSnapshot): YtdSnapshot {
        fun Money.minus(other: Money): Money = Money(amount - other.amount, currency)

        fun <K> deltaMap(o: Map<K, Money>, c: Map<K, Money>): Map<K, Money> {
            val keys = (o.keys + c.keys)
            return keys.associateWith { k ->
                val cv = c[k] ?: Money(0L)
                val ov = o[k] ?: Money(0L)
                cv.minus(ov)
            }.filterValues { it.amount != 0L }
        }

        return corrected.copy(
            earningsByCode = deltaMap(original.earningsByCode, corrected.earningsByCode),
            employeeTaxesByRuleId = deltaMap(original.employeeTaxesByRuleId, corrected.employeeTaxesByRuleId),
            employerTaxesByRuleId = deltaMap(original.employerTaxesByRuleId, corrected.employerTaxesByRuleId),
            deductionsByCode = deltaMap(original.deductionsByCode, corrected.deductionsByCode),
            wagesByBasis = deltaMap(original.wagesByBasis, corrected.wagesByBasis),
            employerContributionsByCode = deltaMap(original.employerContributionsByCode, corrected.employerContributionsByCode),
        )
    }

    private fun notFound(message: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, message)

    private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
}
