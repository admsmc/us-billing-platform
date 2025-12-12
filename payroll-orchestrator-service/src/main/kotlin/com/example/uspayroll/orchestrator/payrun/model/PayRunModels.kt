package com.example.uspayroll.orchestrator.payrun.model

import java.time.Instant

enum class PayRunStatus {
    QUEUED,
    RUNNING,
    FINALIZED,
    PARTIALLY_FINALIZED,
    FAILED,
}

enum class PayRunItemStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
}

enum class PayRunType {
    REGULAR,
    OFF_CYCLE,
    ADJUSTMENT,
    VOID,
    REISSUE,
}

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

enum class PaymentStatus {
    UNPAID,
    PAYING,
    PAID,
    PARTIALLY_PAID,
    FAILED,
}

data class PayRunRecord(
    val employerId: String,
    val payRunId: String,
    val payPeriodId: String,
    val runType: PayRunType,
    val runSequence: Int,
    val status: PayRunStatus,
    val approvalStatus: ApprovalStatus,
    val paymentStatus: PaymentStatus,
    val requestedIdempotencyKey: String?,
    val leaseOwner: String?,
    val leaseExpiresAt: Instant?,
)

data class PayRunItemRecord(
    val employerId: String,
    val payRunId: String,
    val employeeId: String,
    val status: PayRunItemStatus,
    val paycheckId: String?,
    val attemptCount: Int,
    val lastError: String?,
)

data class PayRunStatusCounts(
    val total: Int,
    val queued: Int,
    val running: Int,
    val succeeded: Int,
    val failed: Int,
)
