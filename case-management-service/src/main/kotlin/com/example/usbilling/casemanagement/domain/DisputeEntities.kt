package com.example.usbilling.casemanagement.domain

import java.time.LocalDateTime

/**
 * Billing dispute submitted by customer.
 */
data class BillingDispute(
    val disputeId: String,
    val utilityId: String,
    val customerId: String,
    val accountId: String,
    val billId: String?,
    val disputeType: DisputeType,
    val disputeReason: String,
    val disputedAmountCents: Long?,
    val status: DisputeStatus,
    val priority: DisputePriority,
    val submittedAt: LocalDateTime,
    val resolvedAt: LocalDateTime?,
    val caseId: String?, // Link to case if escalated
    val createdBy: String,
)

/**
 * Investigation record for a dispute.
 */
data class DisputeInvestigation(
    val investigationId: String,
    val disputeId: String,
    val assignedTo: String,
    val investigationNotes: String?,
    val meterTestRequested: Boolean,
    val meterTestResult: String?,
    val fieldVisitRequired: Boolean,
    val fieldVisitCompletedAt: LocalDateTime?,
    val findings: String?,
    val recommendation: InvestigationRecommendation?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)

/**
 * Resolution of a dispute.
 */
data class DisputeResolution(
    val resolutionId: String,
    val disputeId: String,
    val resolutionType: ResolutionType,
    val adjustmentAmountCents: Long?,
    val adjustmentApplied: Boolean,
    val resolutionNotes: String,
    val customerNotifiedAt: LocalDateTime?,
    val resolvedBy: String,
    val resolvedAt: LocalDateTime,
)

enum class DisputeType {
    HIGH_BILL,
    ESTIMATED_BILL,
    METER_ACCURACY,
    SERVICE_QUALITY,
    CHARGE_ERROR,
}

enum class DisputeStatus {
    SUBMITTED,
    INVESTIGATING,
    RESOLVED,
    CLOSED,
    ESCALATED,
}

enum class DisputePriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class InvestigationRecommendation {
    APPROVE_ADJUSTMENT,
    DENY,
    PARTIAL_ADJUSTMENT,
    ESCALATE,
}

enum class ResolutionType {
    ADJUSTMENT_APPROVED,
    DENIED,
    PARTIAL_ADJUSTMENT,
    ESCALATED_TO_COMMISSION,
}
