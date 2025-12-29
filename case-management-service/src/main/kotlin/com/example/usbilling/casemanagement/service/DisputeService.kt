package com.example.usbilling.casemanagement.service

import com.example.usbilling.casemanagement.domain.*
import com.example.usbilling.casemanagement.repository.DisputeInvestigationRepository
import com.example.usbilling.casemanagement.repository.DisputeRepository
import com.example.usbilling.casemanagement.repository.DisputeResolutionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.*

@Service
class DisputeService(
    private val disputeRepository: DisputeRepository,
    private val investigationRepository: DisputeInvestigationRepository,
    private val resolutionRepository: DisputeResolutionRepository,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Submit a new billing dispute.
     */
    fun submitDispute(
        utilityId: String,
        customerId: String,
        accountId: String,
        billId: String?,
        disputeType: DisputeType,
        disputeReason: String,
        disputedAmountCents: Long?,
        submittedBy: String,
    ): BillingDispute {
        require(disputeReason.isNotBlank()) { "Dispute reason is required" }

        val priority = determinePriority(disputeType, disputedAmountCents)

        val dispute = BillingDispute(
            disputeId = UUID.randomUUID().toString(),
            utilityId = utilityId,
            customerId = customerId,
            accountId = accountId,
            billId = billId,
            disputeType = disputeType,
            disputeReason = disputeReason,
            disputedAmountCents = disputedAmountCents,
            status = DisputeStatus.SUBMITTED,
            priority = priority,
            submittedAt = LocalDateTime.now(),
            resolvedAt = null,
            caseId = null,
            createdBy = submittedBy,
        )

        val saved = disputeRepository.save(dispute)

        logger.info("Dispute submitted: ${ saved.disputeId} for customer=$customerId, type=$disputeType")

        // TODO: Publish DISPUTE_SUBMITTED event

        return saved
    }

    /**
     * Start investigation of a dispute.
     */
    fun startInvestigation(
        disputeId: String,
        assignedTo: String,
    ): DisputeInvestigation {
        val dispute = disputeRepository.findById(disputeId)
            ?: throw IllegalArgumentException("Dispute not found: $disputeId")

        if (dispute.status != DisputeStatus.SUBMITTED) {
            throw IllegalStateException("Cannot investigate dispute in status: ${dispute.status}")
        }

        // Update dispute status
        val updatedDispute = dispute.copy(status = DisputeStatus.INVESTIGATING)
        disputeRepository.save(updatedDispute)

        // Create investigation
        val now = LocalDateTime.now()
        val investigation = DisputeInvestigation(
            investigationId = UUID.randomUUID().toString(),
            disputeId = disputeId,
            assignedTo = assignedTo,
            investigationNotes = null,
            meterTestRequested = false,
            meterTestResult = null,
            fieldVisitRequired = false,
            fieldVisitCompletedAt = null,
            findings = null,
            recommendation = null,
            createdAt = now,
            updatedAt = now,
        )

        val saved = investigationRepository.save(investigation)

        logger.info("Investigation started for dispute=$disputeId, assigned to=$assignedTo")

        // TODO: Publish DISPUTE_INVESTIGATING event

        return saved
    }

    /**
     * Update investigation details.
     */
    fun updateInvestigation(
        disputeId: String,
        investigationNotes: String?,
        meterTestRequested: Boolean?,
        meterTestResult: String?,
        fieldVisitRequired: Boolean?,
        fieldVisitCompletedAt: LocalDateTime?,
        findings: String?,
        recommendation: InvestigationRecommendation?,
    ): DisputeInvestigation {
        val existing = investigationRepository.findByDisputeId(disputeId)
            ?: throw IllegalArgumentException("No investigation found for dispute: $disputeId")

        val updated = existing.copy(
            investigationNotes = investigationNotes ?: existing.investigationNotes,
            meterTestRequested = meterTestRequested ?: existing.meterTestRequested,
            meterTestResult = meterTestResult ?: existing.meterTestResult,
            fieldVisitRequired = fieldVisitRequired ?: existing.fieldVisitRequired,
            fieldVisitCompletedAt = fieldVisitCompletedAt ?: existing.fieldVisitCompletedAt,
            findings = findings ?: existing.findings,
            recommendation = recommendation ?: existing.recommendation,
            updatedAt = LocalDateTime.now(),
        )

        return investigationRepository.save(updated)
    }

    /**
     * Resolve a dispute.
     */
    fun resolveDispute(
        disputeId: String,
        resolutionType: ResolutionType,
        adjustmentAmountCents: Long?,
        resolutionNotes: String,
        resolvedBy: String,
    ): DisputeResolution {
        val dispute = disputeRepository.findById(disputeId)
            ?: throw IllegalArgumentException("Dispute not found: $disputeId")

        if (dispute.status == DisputeStatus.RESOLVED || dispute.status == DisputeStatus.CLOSED) {
            throw IllegalStateException("Dispute already resolved")
        }

        // Update dispute status
        val now = LocalDateTime.now()
        val updatedDispute = dispute.copy(
            status = DisputeStatus.RESOLVED,
            resolvedAt = now,
        )
        disputeRepository.save(updatedDispute)

        // Create resolution
        val resolution = DisputeResolution(
            resolutionId = UUID.randomUUID().toString(),
            disputeId = disputeId,
            resolutionType = resolutionType,
            adjustmentAmountCents = adjustmentAmountCents,
            adjustmentApplied = false,
            resolutionNotes = resolutionNotes,
            customerNotifiedAt = null,
            resolvedBy = resolvedBy,
            resolvedAt = now,
        )

        val saved = resolutionRepository.save(resolution)

        logger.info("Dispute resolved: $disputeId, type=$resolutionType, adjustment=$adjustmentAmountCents")

        // TODO: Publish DISPUTE_RESOLVED event
        // TODO: If adjustment approved, call billing-orchestrator to apply credit

        return saved
    }

    /**
     * Escalate dispute to case management.
     */
    fun escalateToCase(disputeId: String, caseId: String): BillingDispute {
        val dispute = disputeRepository.findById(disputeId)
            ?: throw IllegalArgumentException("Dispute not found: $disputeId")

        val updated = dispute.copy(
            status = DisputeStatus.ESCALATED,
            caseId = caseId,
        )

        val saved = disputeRepository.save(updated)

        logger.info("Dispute escalated: $disputeId to case=$caseId")

        return saved
    }

    /**
     * Close a dispute.
     */
    fun closeDispute(disputeId: String): BillingDispute {
        val dispute = disputeRepository.findById(disputeId)
            ?: throw IllegalArgumentException("Dispute not found: $disputeId")

        if (dispute.status != DisputeStatus.RESOLVED) {
            throw IllegalStateException("Can only close resolved disputes")
        }

        val updated = dispute.copy(status = DisputeStatus.CLOSED)
        return disputeRepository.save(updated)
    }

    /**
     * Get dispute by ID with investigation and resolution.
     */
    fun getDisputeDetails(disputeId: String): DisputeDetails? {
        val dispute = disputeRepository.findById(disputeId) ?: return null
        val investigation = investigationRepository.findByDisputeId(disputeId)
        val resolution = resolutionRepository.findByDisputeId(disputeId)

        return DisputeDetails(dispute, investigation, resolution)
    }

    /**
     * Get customer's disputes.
     */
    fun getCustomerDisputes(customerId: String, limit: Int = 50): List<BillingDispute> =
        disputeRepository.findByCustomerId(customerId, limit)

    /**
     * Get disputes by status (for CSR dashboard).
     */
    fun getDisputesByStatus(status: DisputeStatus, limit: Int = 100): List<BillingDispute> =
        disputeRepository.findByStatus(status, limit)

    /**
     * Determine priority based on dispute type and amount.
     */
    private fun determinePriority(disputeType: DisputeType, disputedAmountCents: Long?): DisputePriority {
        // High amount disputes get higher priority
        if (disputedAmountCents != null && disputedAmountCents >= 50000) { // $500+
            return DisputePriority.HIGH
        }

        return when (disputeType) {
            DisputeType.METER_ACCURACY -> DisputePriority.HIGH
            DisputeType.SERVICE_QUALITY -> DisputePriority.HIGH
            DisputeType.HIGH_BILL -> DisputePriority.MEDIUM
            DisputeType.ESTIMATED_BILL -> DisputePriority.MEDIUM
            DisputeType.CHARGE_ERROR -> DisputePriority.MEDIUM
        }
    }
}

/**
 * Complete dispute details with investigation and resolution.
 */
data class DisputeDetails(
    val dispute: BillingDispute,
    val investigation: DisputeInvestigation?,
    val resolution: DisputeResolution?,
)
