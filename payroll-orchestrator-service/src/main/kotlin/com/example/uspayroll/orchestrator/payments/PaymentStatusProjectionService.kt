package com.example.uspayroll.orchestrator.payments

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.uspayroll.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.uspayroll.orchestrator.payments.model.PaycheckPaymentStatus
import com.example.uspayroll.orchestrator.payments.persistence.PaycheckPaymentRepository
import com.example.uspayroll.orchestrator.payments.persistence.PaymentStatusProjectionRepository
import com.example.uspayroll.orchestrator.payrun.model.PaymentStatus
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PaymentStatusProjectionService(
    private val payRunRepository: PayRunRepository,
    private val repo: PaymentStatusProjectionRepository,
    private val paycheckPayments: PaycheckPaymentRepository,
) {
    @Transactional
    fun applyPaymentStatusChanged(evt: PaycheckPaymentStatusChangedEvent) {
        val mapped = when (evt.status) {
            PaycheckPaymentLifecycleStatus.SETTLED -> PaymentStatus.PAID
            PaycheckPaymentLifecycleStatus.FAILED -> PaymentStatus.FAILED
            PaycheckPaymentLifecycleStatus.CREATED,
            PaycheckPaymentLifecycleStatus.SUBMITTED,
            -> PaymentStatus.PAYING
        }

        val projectionStatus = PaycheckPaymentStatus.valueOf(evt.status.name)
        val updatedProjection = paycheckPayments.updateStatusByPaycheck(
            employerId = evt.employerId,
            paycheckId = evt.paycheckId,
            status = projectionStatus,
        )

        // If projection is missing (eg. status events replayed before initiation projection was written),
        // seed it from the paycheck row.
        if (updatedProjection == 0) {
            val c = paycheckPayments.findCandidateByPaycheck(evt.employerId, evt.paycheckId)
            if (c != null) {
                paycheckPayments.insertIfAbsent(
                    employerId = evt.employerId,
                    paymentId = evt.paymentId,
                    paycheckId = evt.paycheckId,
                    payRunId = c.payRunId,
                    employeeId = c.employeeId,
                    payPeriodId = c.payPeriodId,
                    currency = c.currency,
                    netCents = c.netCents,
                    status = projectionStatus,
                )
            }
        }

        repo.updatePaycheckPaymentStatus(
            employerId = evt.employerId,
            paycheckId = evt.paycheckId,
            paymentStatus = mapped,
        )

        val counts = repo.getPayRunPaymentCounts(evt.employerId, evt.payRunId)
        if (counts.total <= 0) return

        val runStatus = when {
            counts.paid == counts.total -> PaymentStatus.PAID
            counts.failed == counts.total -> PaymentStatus.FAILED
            counts.paid > 0 && counts.failed > 0 -> PaymentStatus.PARTIALLY_PAID
            else -> PaymentStatus.PAYING
        }

        payRunRepository.setPaymentStatus(
            employerId = evt.employerId,
            payRunId = evt.payRunId,
            paymentStatus = runStatus,
        )
    }
}
