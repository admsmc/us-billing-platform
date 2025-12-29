package com.example.usbilling.casemanagement.service

import com.example.usbilling.casemanagement.domain.CaseCategory
import com.example.usbilling.casemanagement.domain.CasePriority
import com.example.usbilling.casemanagement.domain.CaseRecord
import com.example.usbilling.casemanagement.repository.CaseRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Case routing service - handles auto-assignment and escalation.
 *
 * Assignment rules:
 * 1. Category-based routing (billing → billing team, etc.)
 * 2. Priority-based routing (critical → supervisor)
 * 3. Round-robin within team
 * 4. Escalation based on age and priority
 */
@Service
class CaseRoutingService(
    private val caseRepository: CaseRepository,
    @Value("\${case-management.auto-assignment.enabled:true}")
    private val autoAssignmentEnabled: Boolean,
    @Value("\${case-management.auto-assignment.default-team:general-support}")
    private val defaultTeam: String,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    // Team assignments by category
    private val categoryTeamMap = mapOf(
        CaseCategory.BILLING to "billing-team",
        CaseCategory.PAYMENT to "payments-team",
        CaseCategory.METER to "field-operations",
        CaseCategory.SERVICE_QUALITY to "service-quality-team",
        CaseCategory.OUTAGE to "outage-team",
        CaseCategory.CONNECTION to "field-operations",
        CaseCategory.DISCONNECTION to "field-operations",
        CaseCategory.ACCOUNT to "customer-service",
        CaseCategory.OTHER to defaultTeam,
    )

    // Supervisors by team (for escalation)
    private val teamSupervisors = mapOf(
        "billing-team" to "billing-supervisor",
        "payments-team" to "payments-supervisor",
        "field-operations" to "field-supervisor",
        "service-quality-team" to "service-supervisor",
        "outage-team" to "outage-supervisor",
        "customer-service" to "cs-supervisor",
        defaultTeam to "general-supervisor",
    )

    fun autoAssignCase(caseRecord: CaseRecord) {
        if (!autoAssignmentEnabled) {
            logger.debug("Auto-assignment disabled, skipping case ${caseRecord.caseId}")
            return
        }

        val assignedTeam = determineTeam(caseRecord)
        val assignedTo = if (caseRecord.priority == CasePriority.CRITICAL) {
            // Escalate critical cases directly to supervisor
            teamSupervisors[assignedTeam]
        } else {
            // Round-robin assignment to team members
            assignToTeamMember(assignedTeam)
        }

        val updated = caseRecord.copy(
            assignedTeam = assignedTeam,
            assignedTo = assignedTo,
        )

        caseRepository.save(updated)
        logger.info(
            "Auto-assigned case ${caseRecord.caseNumber} to team=$assignedTeam, user=$assignedTo",
        )
    }

    fun escalateCase(caseRecord: CaseRecord, reason: String): CaseRecord {
        val supervisor = teamSupervisors[caseRecord.assignedTeam] ?: teamSupervisors[defaultTeam]

        val updated = caseRecord.copy(
            assignedTo = supervisor,
            priority = when (caseRecord.priority) {
                CasePriority.LOW -> CasePriority.MEDIUM
                CasePriority.MEDIUM -> CasePriority.HIGH
                CasePriority.HIGH, CasePriority.CRITICAL -> CasePriority.CRITICAL
            },
        )

        caseRepository.save(updated)
        logger.info("Escalated case ${caseRecord.caseNumber} to $supervisor. Reason: $reason")

        return updated
    }

    private fun determineTeam(caseRecord: CaseRecord): String {
        return categoryTeamMap[caseRecord.caseCategory] ?: defaultTeam
    }

    /**
     * Round-robin assignment to team members.
     * In production, this would query a team_member table and track assignment counts.
     * For now, we return a placeholder team identifier.
     */
    private fun assignToTeamMember(team: String): String? {
        // Placeholder: in production, implement round-robin logic
        // Example: query team_member where team = $team order by assignment_count asc limit 1
        return null // null means assigned to team queue, not individual
    }
}
