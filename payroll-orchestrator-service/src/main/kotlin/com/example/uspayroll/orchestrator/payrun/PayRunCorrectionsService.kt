package com.example.uspayroll.orchestrator.payrun

import com.example.uspayroll.orchestrator.events.PayRunOutboxEnqueuer
import com.example.uspayroll.orchestrator.payrun.model.ApprovalStatus
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.orchestrator.payrun.model.PayRunType
import com.example.uspayroll.orchestrator.payrun.model.PaymentStatus
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunRepository
import com.example.uspayroll.orchestrator.persistence.PaycheckAuditStoreRepository
import com.example.uspayroll.orchestrator.persistence.PaycheckStoreRepository
import com.example.uspayroll.payroll.engine.PayrollEngine
import com.example.uspayroll.payroll.model.DeductionLine
import com.example.uspayroll.payroll.model.EarningLine
import com.example.uspayroll.payroll.model.EmployerContributionLine
import com.example.uspayroll.payroll.model.PaycheckResult
import com.example.uspayroll.payroll.model.TaxLine
import com.example.uspayroll.payroll.model.audit.PaycheckAudit
import com.example.uspayroll.shared.EmployeeId
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.shared.PayRunId
import com.example.uspayroll.shared.PaycheckId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class PayRunCorrectionsService(
    private val payRunRepository: PayRunRepository,
    private val payRunItemRepository: PayRunItemRepository,
    private val paycheckStoreRepository: PaycheckStoreRepository,
    private val paycheckAuditStoreRepository: PaycheckAuditStoreRepository,
    private val outboxEnqueuer: PayRunOutboxEnqueuer,
) {

    data class StartCorrectionResult(
        val employerId: String,
        val sourcePayRunId: String,
        val correctionPayRunId: String,
        val runType: PayRunType,
        val runSequence: Int,
        val status: PayRunStatus,
        val totalItems: Int,
        val succeeded: Int,
        val failed: Int,
        val created: Boolean,
    )

    @Transactional
    fun startVoid(employerId: String, sourcePayRunId: String, requestedPayRunId: String? = null, runSequenceOverride: Int? = null, idempotencyKey: String? = null): StartCorrectionResult {
        val source = payRunRepository.findPayRun(employerId, sourcePayRunId)
            ?: notFound("source payrun not found")

        if (source.approvalStatus != ApprovalStatus.APPROVED) {
            conflict("cannot void: source payrun not approved")
        }
        if (source.paymentStatus != PaymentStatus.PAID) {
            conflict("cannot void: source payrun not fully paid")
        }

        val runSequence = runSequenceOverride ?: source.runSequence

        val payRunId = requestedPayRunId ?: "void-$sourcePayRunId"

        return startCorrectionInternal(
            employerId = employerId,
            sourcePayRunId = sourcePayRunId,
            correctionRunType = PayRunType.VOID,
            runSequence = runSequence,
            requestedPayRunId = payRunId,
            idempotencyKey = idempotencyKey,
        ) { paycheck -> paycheck.negatedForVoid() }
    }

    @Transactional
    fun startReissue(employerId: String, sourcePayRunId: String, requestedPayRunId: String? = null, runSequenceOverride: Int? = null, idempotencyKey: String? = null): StartCorrectionResult {
        val source = payRunRepository.findPayRun(employerId, sourcePayRunId)
            ?: notFound("source payrun not found")

        if (source.approvalStatus != ApprovalStatus.APPROVED) {
            conflict("cannot reissue: source payrun not approved")
        }

        val runSequence = runSequenceOverride ?: source.runSequence
        val payRunId = requestedPayRunId ?: "reissue-$sourcePayRunId"

        return startCorrectionInternal(
            employerId = employerId,
            sourcePayRunId = sourcePayRunId,
            correctionRunType = PayRunType.REISSUE,
            runSequence = runSequence,
            requestedPayRunId = payRunId,
            idempotencyKey = idempotencyKey,
        ) { paycheck -> paycheck }
    }

    private fun computedTerminalStatus(total: Int, succeeded: Int, failed: Int): PayRunStatus = when {
        total == 0 -> PayRunStatus.FAILED
        failed == 0 && succeeded == total -> PayRunStatus.FINALIZED
        succeeded > 0 && failed > 0 -> PayRunStatus.PARTIALLY_FINALIZED
        succeeded == 0 && failed == total -> PayRunStatus.FAILED
        else -> PayRunStatus.FAILED
    }

    private fun PaycheckResult.negatedForVoid(): PaycheckResult {
        fun Money.negate(): Money = Money(-amount, currency)

        fun EarningLine.negate(): EarningLine = copy(amount = amount.negate(), rate = rate?.negate())
        fun TaxLine.negate(): TaxLine = copy(basis = basis.negate(), amount = amount.negate())
        fun DeductionLine.negate(): DeductionLine = copy(amount = amount.negate())
        fun EmployerContributionLine.negate(): EmployerContributionLine = copy(amount = amount.negate())

        fun <K> Map<K, Money>.negated(): Map<K, Money> = entries.associate { (k, v) -> k to v.negate() }

        val ytd = ytdAfter.copy(
            earningsByCode = ytdAfter.earningsByCode.negated(),
            employeeTaxesByRuleId = ytdAfter.employeeTaxesByRuleId.negated(),
            employerTaxesByRuleId = ytdAfter.employerTaxesByRuleId.negated(),
            deductionsByCode = ytdAfter.deductionsByCode.negated(),
            wagesByBasis = ytdAfter.wagesByBasis.negated(),
            employerContributionsByCode = ytdAfter.employerContributionsByCode.negated(),
        )

        return copy(
            earnings = earnings.map { it.negate() },
            employeeTaxes = employeeTaxes.map { it.negate() },
            employerTaxes = employerTaxes.map { it.negate() },
            deductions = deductions.map { it.negate() },
            employerContributions = employerContributions.map { it.negate() },
            gross = gross.negate(),
            net = net.negate(),
            ytdAfter = ytd,
        )
    }

    private fun PaycheckAudit.forCorrection(newPaycheckId: String, newPayRunId: String, now: Instant, negate: Boolean): PaycheckAudit {
        fun neg(x: Long): Long = if (negate) -x else x

        return copy(
            engineVersion = PayrollEngine.version(),
            computedAt = now,
            paycheckId = newPaycheckId,
            payRunId = newPayRunId,
            cashGrossCents = neg(cashGrossCents),
            grossTaxableCents = neg(grossTaxableCents),
            federalTaxableCents = neg(federalTaxableCents),
            stateTaxableCents = neg(stateTaxableCents),
            socialSecurityWagesCents = neg(socialSecurityWagesCents),
            medicareWagesCents = neg(medicareWagesCents),
            supplementalWagesCents = neg(supplementalWagesCents),
            futaWagesCents = neg(futaWagesCents),
            employeeTaxCents = neg(employeeTaxCents),
            employerTaxCents = neg(employerTaxCents),
            preTaxDeductionCents = neg(preTaxDeductionCents),
            postTaxDeductionCents = neg(postTaxDeductionCents),
            garnishmentCents = neg(garnishmentCents),
            netCents = neg(netCents),
        )
    }

    private fun startCorrectionInternal(
        employerId: String,
        sourcePayRunId: String,
        correctionRunType: PayRunType,
        runSequence: Int,
        requestedPayRunId: String,
        idempotencyKey: String?,
        transformPaycheck: (PaycheckResult) -> PaycheckResult,
    ): StartCorrectionResult {
        val source = payRunRepository.findPayRun(employerId, sourcePayRunId)
            ?: notFound("source payrun not found")

        val succeededSourcePaychecks = payRunItemRepository.listSucceededPaychecks(employerId, sourcePayRunId)
        if (succeededSourcePaychecks.isEmpty()) {
            conflict("source payrun has no succeeded paychecks to correct")
        }

        val correctionPayRunId = requestedPayRunId.ifBlank { "run-${UUID.randomUUID()}" }

        val createOrGet = payRunRepository.createOrGetPayRun(
            employerId = employerId,
            payRunId = correctionPayRunId,
            payPeriodId = source.payPeriodId,
            runType = correctionRunType,
            runSequence = runSequence,
            requestedIdempotencyKey = idempotencyKey,
        )

        val correctionRun = createOrGet.payRun

        val acceptedLink = payRunRepository.acceptCorrectionOfPayRunId(
            employerId = employerId,
            payRunId = correctionRun.payRunId,
            correctionOfPayRunId = sourcePayRunId,
        )
        if (!acceptedLink) {
            conflict("correction payrun is linked to a different source payrun")
        }

        val employeeIds = succeededSourcePaychecks.map { (employeeId, _) -> employeeId }
        payRunItemRepository.upsertQueuedItems(
            employerId = employerId,
            payRunId = correctionRun.payRunId,
            employeeIds = employeeIds,
        )

        val sourcePaycheckIdByEmployeeId = succeededSourcePaychecks.toMap()

        val employer = EmployerId(employerId)
        val now = Instant.now()

        employeeIds.forEach { employeeId ->
            val originalPaycheckId = sourcePaycheckIdByEmployeeId.getValue(employeeId)

            // Per-item idempotency: if already SUCCEEDED, skip.
            val current = payRunItemRepository.findItem(employerId, correctionRun.payRunId, employeeId)
            if (current?.status?.name == "SUCCEEDED") {
                return@forEach
            }

            // Claim + compute.
            val claim = payRunItemRepository.claimItem(
                employerId = employerId,
                payRunId = correctionRun.payRunId,
                employeeId = employeeId,
                requeueStaleMillis = 0L,
            )

            // If claim failed or not queued, best-effort continue.
            if (claim == null) {
                return@forEach
            }
            if (!claim.claimed && claim.item.status.name != "RUNNING") {
                // Someone else holds it; treat as pending.
                return@forEach
            }

            val newPaycheckId = payRunItemRepository.getOrAssignPaycheckId(
                employerId = employerId,
                payRunId = correctionRun.payRunId,
                employeeId = employeeId,
            )

            @Suppress("TooGenericExceptionCaught")
            try {
                val original = paycheckStoreRepository.findPaycheck(employer, originalPaycheckId)
                    ?: error("source paycheck not found: $originalPaycheckId")

                val transformed = transformPaycheck(original)
                    .copy(
                        paycheckId = PaycheckId(newPaycheckId),
                        payRunId = PayRunId(correctionRun.payRunId),
                        employerId = employer,
                        employeeId = EmployeeId(employeeId),
                    )

                paycheckStoreRepository.insertFinalPaycheckIfAbsent(
                    employerId = employer,
                    paycheckId = transformed.paycheckId.value,
                    payRunId = correctionRun.payRunId,
                    employeeId = transformed.employeeId.value,
                    payPeriodId = transformed.period.id,
                    runType = correctionRunType.name,
                    runSequence = correctionRun.runSequence,
                    checkDateIso = transformed.period.checkDate.toString(),
                    grossCents = transformed.gross.amount,
                    netCents = transformed.net.amount,
                    version = 1,
                    payload = transformed,
                )

                paycheckStoreRepository.setCorrectionOfPaycheckIdIfNull(
                    employerId = employer,
                    paycheckId = transformed.paycheckId.value,
                    correctionOfPaycheckId = originalPaycheckId,
                )

                val originalAudit = paycheckAuditStoreRepository.findAudit(employer, originalPaycheckId)
                    ?: error("source paycheck audit not found: $originalPaycheckId")

                val correctionAudit = originalAudit.forCorrection(
                    newPaycheckId = transformed.paycheckId.value,
                    newPayRunId = correctionRun.payRunId,
                    now = now,
                    negate = correctionRunType == PayRunType.VOID,
                )

                paycheckAuditStoreRepository.insertAuditIfAbsent(correctionAudit)

                payRunItemRepository.markSucceeded(
                    employerId = employerId,
                    payRunId = correctionRun.payRunId,
                    employeeId = employeeId,
                    paycheckId = transformed.paycheckId.value,
                )
            } catch (t: Exception) {
                payRunItemRepository.markFailed(
                    employerId = employerId,
                    payRunId = correctionRun.payRunId,
                    employeeId = employeeId,
                    error = t.message ?: t::class.java.name,
                )
            }
        }

        val counts = payRunItemRepository.countsForPayRun(employerId, correctionRun.payRunId)
        val terminalStatus = computedTerminalStatus(total = counts.total, succeeded = counts.succeeded, failed = counts.failed)

        outboxEnqueuer.finalizePayRunAndEnqueueOutboxEvents(
            employerId = employerId,
            payRunId = correctionRun.payRunId,
            payPeriodId = source.payPeriodId,
            status = terminalStatus,
            total = counts.total,
            succeeded = counts.succeeded,
            failed = counts.failed,
        )

        return StartCorrectionResult(
            employerId = employerId,
            sourcePayRunId = sourcePayRunId,
            correctionPayRunId = correctionRun.payRunId,
            runType = correctionRunType,
            runSequence = correctionRunSequenceOrFallback(correctionRun.runSequence, runSequence),
            status = terminalStatus,
            totalItems = counts.total,
            succeeded = counts.succeeded,
            failed = counts.failed,
            created = createOrGet.wasCreated,
        )
    }

    private fun correctionRunSequenceOrFallback(actual: Int, requested: Int): Int = if (actual > 0) actual else requested

    private fun notFound(message: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, message)

    private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)
}
