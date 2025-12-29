package com.example.usbilling.casemanagement.domain

import java.time.LocalDateTime

/**
 * Case record entity - maps to case_record table in customer database.
 */
data class CaseRecord(
    val caseId: String,
    val caseNumber: String,
    val utilityId: String,
    val accountId: String?,
    val customerId: String?,
    val caseType: CaseType,
    val caseCategory: CaseCategory,
    val status: CaseStatus,
    val priority: CasePriority,
    val title: String,
    val description: String?,
    val openedBy: String,
    val openedAt: LocalDateTime,
    val assignedTo: String?,
    val assignedTeam: String?,
    val resolvedAt: LocalDateTime?,
    val closedAt: LocalDateTime?,
    val resolutionNotes: String?,
)

/**
 * Case note entity - maps to case_note table.
 */
data class CaseNote(
    val noteId: String,
    val caseId: String,
    val noteText: String,
    val noteType: NoteType,
    val createdBy: String,
    val createdAt: LocalDateTime,
    val customerVisible: Boolean,
)

/**
 * Case status history entity - maps to case_status_history table.
 */
data class CaseStatusHistory(
    val historyId: String,
    val caseId: String,
    val fromStatus: CaseStatus?,
    val toStatus: CaseStatus,
    val changedAt: LocalDateTime,
    val changedBy: String,
    val reason: String?,
)

enum class CaseType {
    SERVICE_REQUEST,
    COMPLAINT,
    DISPUTE,
    INQUIRY,
    FEEDBACK,
}

enum class CaseCategory {
    BILLING,
    PAYMENT,
    METER,
    SERVICE_QUALITY,
    OUTAGE,
    CONNECTION,
    DISCONNECTION,
    ACCOUNT,
    OTHER,
}

enum class CaseStatus {
    OPEN,
    IN_PROGRESS,
    PENDING_CUSTOMER,
    PENDING_INTERNAL,
    RESOLVED,
    CLOSED,
    CANCELLED,
}

enum class CasePriority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class NoteType {
    INTERNAL,
    CUSTOMER,
    SYSTEM,
    RESOLUTION,
}
