package com.example.uspayroll.orchestrator.payments

import com.example.uspayroll.messaging.events.payments.PaycheckPaymentLifecycleStatus
import com.example.uspayroll.messaging.events.payments.PaycheckPaymentStatusChangedEvent
import com.example.uspayroll.orchestrator.payments.persistence.PaymentStatusProjectionRepository
import com.example.uspayroll.orchestrator.payrun.model.PaymentStatus
import com.example.uspayroll.orchestrator.payrun.persistence.PayRunRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PaymentStatusProjectionService(
    private val payRunRepository: PayRunRepository,
    private val repo: PaymentStatusProjectionRepository,
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
