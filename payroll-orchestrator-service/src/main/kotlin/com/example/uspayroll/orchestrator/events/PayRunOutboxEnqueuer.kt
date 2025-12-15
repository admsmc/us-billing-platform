package com.example.uspayroll.orchestrator.events

import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerAction
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerAuditAggregates
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerDeductionLine
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEarningLine
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEmployerContributionLine
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerEvent
import com.example.uspayroll.messaging.events.reporting.PaycheckLedgerTaxLine
import com.example.uspayroll.orchestrator.events.kafka.KafkaEventsProperties
import com.example.uspayroll.orchestrator.outbox.OutboxRepository
import com.example.uspayroll.orchestrator.payrun.model.PayRunStatus
import com.example.uspayroll.orchestrator.payrun.model.PayRunType
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunItemRepository
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunRepository
import com.example.uspayroll.orchestrator.persistence.PaycheckAuditStoreRepository
import com.example.uspayroll.orchestrator.persistence.PaycheckStoreRepository
import com.example.uspayroll.shared.EmployerId
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
class PayRunOutboxEnqueuer(
    private val payRunRepository: PayRunRepository,
    private val payRunItemRepository: PayRunItemRepository,
    private val paycheckStoreRepository: PaycheckStoreRepository,
    private val paycheckAuditStoreRepository: PaycheckAuditStoreRepository,
    private val outboxRepository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val kafkaProps: KafkaEventsProperties,
) {

    /**
     * Transactionally:
     * 1) writes pay_run terminal status
     * 2) enqueues outbox events for payrun + each succeeded paycheck
     */
    @Transactional
    fun finalizePayRunAndEnqueueOutboxEvents(employerId: String, payRunId: String, payPeriodId: String, status: PayRunStatus, total: Int, succeeded: Int, failed: Int) {
        payRunRepository.setFinalStatusAndReleaseLease(
            employerId = employerId,
            payRunId = payRunId,
            status = status,
        )

        val now = Instant.now()

        val payRunFinalized = PayRunFinalizedEvent(
            eventId = "payrun-finalized:$employerId:$payRunId",
            occurredAt = now,
            employerId = employerId,
            payRunId = payRunId,
            payPeriodId = payPeriodId,
            status = status.name,
            total = total,
            succeeded = succeeded,
            failed = failed,
        )

        outboxRepository.enqueue(
            topic = kafkaProps.payRunFinalizedTopic,
            // Partition key: per (employer, payRun) to preserve ordering for a run.
            eventKey = "$employerId:$payRunId",
            eventType = "PayRunFinalized",
            eventId = payRunFinalized.eventId,
            aggregateId = payRunId,
            payloadJson = objectMapper.writeValueAsString(payRunFinalized),
            now = now,
        )

        val succeededPaychecks = payRunItemRepository.listSucceededPaychecks(employerId, payRunId)

        val paycheckRows = succeededPaychecks.map { (employeeId, paycheckId) ->
            val evt = PaycheckFinalizedEvent(
                eventId = "paycheck-finalized:$employerId:$paycheckId",
                occurredAt = now,
                employerId = employerId,
                payRunId = payRunId,
                paycheckId = paycheckId,
                employeeId = employeeId,
            )

            OutboxRepository.PendingOutboxInsert(
                topic = kafkaProps.paycheckFinalizedTopic,
                // Partition key: per (employer, payRun) to keep paychecks for a run ordered.
                eventKey = "$employerId:$payRunId",
                eventType = "PaycheckFinalized",
                eventId = evt.eventId,
                aggregateId = paycheckId,
                payloadJson = objectMapper.writeValueAsString(evt),
            )
        }

        outboxRepository.enqueueBatch(paycheckRows, now = now)
    }

    /**
     * Enqueue one reporting/filings-oriented event per succeeded paycheck *when a payrun is approved*.
     *
     * Idempotency strategy:
     * - deterministic eventId: paycheck-ledger:<action>:<employer>:<paycheck>
     * - outbox_event has a unique constraint on (event_id) so duplicates become a no-op
     */
    @Transactional
    fun enqueuePaycheckLedgerEventsForApprovedPayRun(employerId: String, payRunId: String) {
        val payRun = payRunRepository.findPayRun(employerId, payRunId) ?: return
        val correctionOfPayRunId = payRunRepository.findCorrectionOfPayRunId(employerId, payRunId)

        val action = when (payRun.runType) {
            PayRunType.VOID -> PaycheckLedgerAction.VOIDED
            PayRunType.ADJUSTMENT -> PaycheckLedgerAction.ADJUSTED
            PayRunType.REISSUE -> PaycheckLedgerAction.REISSUED
            else -> PaycheckLedgerAction.COMMITTED
        }

        val succeededPaychecks = payRunItemRepository.listSucceededPaychecks(employerId, payRunId)
        if (succeededPaychecks.isEmpty()) return

        val employer = EmployerId(employerId)
        val now = Instant.now()

        val rows = succeededPaychecks.mapNotNull { (employeeId, paycheckId) ->
            val paycheck = paycheckStoreRepository.findPaycheck(employer, paycheckId) ?: return@mapNotNull null
            val correctionOfPaycheckId = paycheckStoreRepository.findCorrectionOfPaycheckId(employer, paycheckId)
            val audit = paycheckAuditStoreRepository.findAudit(employer, paycheckId)

            val evt = PaycheckLedgerEvent(
                eventId = "paycheck-ledger:${action.name.lowercase()}:$employerId:$paycheckId",
                occurredAt = now,
                action = action,
                employerId = employerId,
                employeeId = employeeId,
                payRunId = payRunId,
                payRunType = payRun.runType.name,
                runSequence = payRun.runSequence,
                payPeriodId = payRun.payPeriodId,
                paycheckId = paycheckId,
                periodStartIso = paycheck.period.dateRange.startInclusive.toString(),
                periodEndIso = paycheck.period.dateRange.endInclusive.toString(),
                checkDateIso = paycheck.period.checkDate.toString(),
                correctionOfPaycheckId = correctionOfPaycheckId,
                correctionOfPayRunId = correctionOfPayRunId,
                currency = paycheck.gross.currency.name,
                grossCents = paycheck.gross.amount,
                netCents = paycheck.net.amount,
                audit = audit?.let {
                    PaycheckLedgerAuditAggregates(
                        cashGrossCents = it.cashGrossCents,
                        grossTaxableCents = it.grossTaxableCents,
                        federalTaxableCents = it.federalTaxableCents,
                        stateTaxableCents = it.stateTaxableCents,
                        socialSecurityWagesCents = it.socialSecurityWagesCents,
                        medicareWagesCents = it.medicareWagesCents,
                        supplementalWagesCents = it.supplementalWagesCents,
                        futaWagesCents = it.futaWagesCents,
                        employeeTaxCents = it.employeeTaxCents,
                        employerTaxCents = it.employerTaxCents,
                        preTaxDeductionCents = it.preTaxDeductionCents,
                        postTaxDeductionCents = it.postTaxDeductionCents,
                        garnishmentCents = it.garnishmentCents,
                    )
                },
                earnings = paycheck.earnings.map {
                    PaycheckLedgerEarningLine(
                        code = it.code.value,
                        category = it.category.name,
                        description = it.description,
                        units = it.units,
                        rateCents = it.rate?.amount,
                        amountCents = it.amount.amount,
                    )
                },
                employeeTaxes = paycheck.employeeTaxes.map {
                    PaycheckLedgerTaxLine(
                        ruleId = it.ruleId,
                        jurisdictionType = it.jurisdiction.type.name,
                        jurisdictionCode = it.jurisdiction.code,
                        description = it.description,
                        basisCents = it.basis.amount,
                        rate = it.rate?.value,
                        amountCents = it.amount.amount,
                    )
                },
                employerTaxes = paycheck.employerTaxes.map {
                    PaycheckLedgerTaxLine(
                        ruleId = it.ruleId,
                        jurisdictionType = it.jurisdiction.type.name,
                        jurisdictionCode = it.jurisdiction.code,
                        description = it.description,
                        basisCents = it.basis.amount,
                        rate = it.rate?.value,
                        amountCents = it.amount.amount,
                    )
                },
                deductions = paycheck.deductions.map {
                    PaycheckLedgerDeductionLine(
                        code = it.code.value,
                        description = it.description,
                        amountCents = it.amount.amount,
                    )
                },
                employerContributions = paycheck.employerContributions.map {
                    PaycheckLedgerEmployerContributionLine(
                        code = it.code.value,
                        description = it.description,
                        amountCents = it.amount.amount,
                    )
                },
            )

            OutboxRepository.PendingOutboxInsert(
                topic = kafkaProps.paycheckLedgerTopic,
                // Partition key: per (employer, employee) to preserve per-employee ledger ordering.
                eventKey = "$employerId:$employeeId",
                eventType = "PaycheckLedger",
                eventId = evt.eventId,
                aggregateId = paycheckId,
                payloadJson = objectMapper.writeValueAsString(evt),
            )
        }

        outboxRepository.enqueueBatch(rows, now = now)
    }
}
