package com.example.usbilling.payments.provider

import com.example.usbilling.messaging.events.payments.PaycheckPaymentLifecycleStatus
import java.time.Instant

/**
 * Integration seam for real payment rails (ACH/wire/check) and bank file providers.
 *
 * IMPORTANT:
 * - This interface intentionally does NOT accept raw bank account / routing numbers.
 * - Real implementations should operate on tokenized/encrypted destination references.
 */
interface PaymentProvider {
    val providerName: String

    fun submitBatch(request: PaymentBatchSubmissionRequest, now: Instant = Instant.now()): PaymentBatchSubmissionResult
}

data class PaymentBatchSubmissionRequest(
    val employerId: String,
    val payRunId: String,
    val batchId: String,
    val payments: List<PaymentInstruction>,
)

data class PaymentInstruction(
    val paymentId: String,
    val paycheckId: String,
    val employeeId: String,
    val payPeriodId: String,
    val currency: String,
    val netCents: Long,
    /** Retry attempt count tracked by payments-service. */
    val attempts: Int,
)

data class PaymentBatchSubmissionResult(
    val provider: String,
    /** Provider-specific reference to a submitted bank file / batch, if available. */
    val providerBatchRef: String?,
    val paymentResults: List<PaymentSubmissionResult>,
)

data class PaymentSubmissionResult(
    val paymentId: String,
    /** Provider-specific reference to the payment (e.g. trace number), if available. */
    val providerPaymentRef: String?,
    /**
     * Optional immediate terminal outcome.
     *
     * For most real providers, this will typically be null (SUBMITTED only), and terminal
     * outcomes will arrive asynchronously (callbacks, settlement files, etc.).
     */
    val terminalStatus: PaycheckPaymentLifecycleStatus?,
    val error: String? = null,
)
