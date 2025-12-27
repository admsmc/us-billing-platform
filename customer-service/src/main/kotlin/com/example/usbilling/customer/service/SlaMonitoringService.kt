package com.example.usbilling.customer.service

import com.example.usbilling.customer.model.*
import com.example.usbilling.customer.repository.JdbcCaseRepository
import com.example.usbilling.shared.UtilityId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Service for monitoring SLA compliance and auto-escalating cases.
 */
@Service
class SlaMonitoringService(
    private val caseRepository: JdbcCaseRepository,
    private val caseManagementService: CaseManagementServiceImpl,
) {
    
    private val logger = LoggerFactory.getLogger(javaClass)
    
    // In-memory SLA configuration - would be loaded from database in production
    private val slaConfig = mapOf(
        CasePriority.CRITICAL to SlaThresholds(
            responseTimeMinutes = 15,
            resolutionTimeMinutes = 240, // 4 hours
            escalationThresholdMinutes = 120 // 2 hours
        ),
        CasePriority.HIGH to SlaThresholds(
            responseTimeMinutes = 60,
            resolutionTimeMinutes = 720, // 12 hours
            escalationThresholdMinutes = 360 // 6 hours
        ),
        CasePriority.MEDIUM to SlaThresholds(
            responseTimeMinutes = 240, // 4 hours
            resolutionTimeMinutes = 2880, // 2 days
            escalationThresholdMinutes = 1440 // 1 day
        ),
        CasePriority.LOW to SlaThresholds(
            responseTimeMinutes = 1440, // 1 day
            resolutionTimeMinutes = 10080, // 7 days
            escalationThresholdMinutes = 5040 // 3.5 days
        )
    )
    
    /**
     * Check all cases for SLA breaches and auto-escalate if necessary.
     */
    fun checkAndEscalateCases(utilityId: UtilityId): SlaCheckResult {
        val now = Instant.now()
        var escalatedCount = 0
        var breachingCount = 0
        val errors = mutableListOf<String>()
        
        // Check each priority level
        for ((priority, thresholds) in slaConfig) {
            try {
                val breachingCases = caseRepository.findCasesBreachingSla(
                    utilityId = utilityId,
                    minutesThreshold = thresholds.escalationThresholdMinutes
                )
                
                breachingCount += breachingCases.size
                
                for (case in breachingCases) {
                    val caseAge = Duration.between(case.createdAt, now).toMinutes()
                    
                    if (shouldEscalate(case, caseAge.toInt(), thresholds)) {
                        try {
                            escalateCase(case, "SLA threshold exceeded: ${caseAge}min > ${thresholds.escalationThresholdMinutes}min")
                            escalatedCount++
                            logger.info("Auto-escalated case ${case.caseNumber} after ${caseAge}min (threshold: ${thresholds.escalationThresholdMinutes}min)")
                        } catch (e: Exception) {
                            logger.error("Failed to escalate case ${case.caseNumber}", e)
                            errors.add("Failed to escalate ${case.caseNumber}: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("Error checking SLA for priority $priority", e)
                errors.add("Error checking $priority priority: ${e.message}")
            }
        }
        
        return SlaCheckResult(
            casesChecked = breachingCount,
            casesEscalated = escalatedCount,
            errors = errors
        )
    }
    
    /**
     * Determine if a case should be escalated based on age and SLA thresholds.
     */
    private fun shouldEscalate(case: CaseRecord, ageMinutes: Int, thresholds: SlaThresholds): Boolean {
        // Already critical - don't escalate further
        if (case.priority == CasePriority.CRITICAL) {
            return false
        }
        
        // Not yet at escalation threshold
        if (ageMinutes < thresholds.escalationThresholdMinutes) {
            return false
        }
        
        // Already escalated status
        if (case.status == CaseStatus.ESCALATED) {
            return false
        }
        
        return true
    }
    
    /**
     * Escalate a case by increasing priority and changing status.
     */
    private fun escalateCase(case: CaseRecord, reason: String) {
        val newPriority = when (case.priority) {
            CasePriority.LOW -> CasePriority.MEDIUM
            CasePriority.MEDIUM -> CasePriority.HIGH
            CasePriority.HIGH -> CasePriority.CRITICAL
            CasePriority.CRITICAL -> CasePriority.CRITICAL // Already at max
        }
        
        val updatedCase = case.copy(
            priority = newPriority,
            status = CaseStatus.ESCALATED,
            updatedAt = Instant.now()
        )
        
        caseRepository.update(updatedCase)
        
        // Record status change
        caseRepository.addStatusHistory(
            CaseStatusHistory(
                historyId = UUID.randomUUID().toString(),
                caseId = case.caseId,
                fromStatus = case.status,
                toStatus = CaseStatus.ESCALATED,
                changedBy = "SYSTEM_SLA_MONITOR",
                changedAt = Instant.now(),
                reason = reason,
                notes = "Auto-escalated from ${case.priority} to $newPriority"
            )
        )
        
        // Add system note
        caseRepository.addNote(
            CaseNote(
                noteId = UUID.randomUUID().toString(),
                caseId = case.caseId,
                noteType = CaseNoteType.ESCALATION_REASON,
                content = reason,
                isInternal = true,
                createdBy = "SYSTEM_SLA_MONITOR",
                createdAt = Instant.now()
            )
        )
    }
    
    /**
     * Get SLA status for a specific case.
     */
    fun getCaseSlaStatus(case: CaseRecord): CaseSlaStatus {
        val thresholds = slaConfig[case.priority] ?: return CaseSlaStatus(
            isBreaching = false,
            minutesUntilBreach = null,
            minutesSinceBreach = null,
            escalationThresholdMinutes = 0,
            resolutionThresholdMinutes = 0
        )
        
        val ageMinutes = Duration.between(case.createdAt, Instant.now()).toMinutes().toInt()
        val escalationThreshold = thresholds.escalationThresholdMinutes
        
        return if (ageMinutes >= escalationThreshold) {
            CaseSlaStatus(
                isBreaching = true,
                minutesUntilBreach = null,
                minutesSinceBreach = ageMinutes - escalationThreshold,
                escalationThresholdMinutes = escalationThreshold,
                resolutionThresholdMinutes = thresholds.resolutionTimeMinutes
            )
        } else {
            CaseSlaStatus(
                isBreaching = false,
                minutesUntilBreach = escalationThreshold - ageMinutes,
                minutesSinceBreach = null,
                escalationThresholdMinutes = escalationThreshold,
                resolutionThresholdMinutes = thresholds.resolutionTimeMinutes
            )
        }
    }
}

data class SlaThresholds(
    val responseTimeMinutes: Int,
    val resolutionTimeMinutes: Int,
    val escalationThresholdMinutes: Int
)

data class SlaCheckResult(
    val casesChecked: Int,
    val casesEscalated: Int,
    val errors: List<String>
)

data class CaseSlaStatus(
    val isBreaching: Boolean,
    val minutesUntilBreach: Int?,
    val minutesSinceBreach: Int?,
    val escalationThresholdMinutes: Int,
    val resolutionThresholdMinutes: Int
)
