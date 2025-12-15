package com.example.uspayroll.orchestrator.payments.model

enum class PaycheckPaymentStatus {
    CREATED,
    SUBMITTED,
    SETTLED,
    FAILED,
}

data class PaycheckPaymentRecord(
    val employerId: String,
    val paymentId: String,
    val paycheckId: String,
    val payRunId: String,
    val employeeId: String,
    val payPeriodId: String,
    val currency: String,
    val netCents: Long,
    val status: PaycheckPaymentStatus,
)
