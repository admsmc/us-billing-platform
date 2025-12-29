package com.example.usbilling.casemanagement

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Case Management Service - handles customer service cases, inquiries, and complaints.
 *
 * This service manages the full lifecycle of customer service cases:
 * 1. Case creation (customer-initiated or CSR-created)
 * 2. Case routing and assignment to teams/individuals
 * 3. Case status tracking (OPEN → IN_PROGRESS → RESOLVED → CLOSED)
 * 4. Case notes and interaction history
 * 5. Case escalation workflows
 * 6. Case metrics and reporting
 *
 * Provides REST APIs for both customer-facing and CSR-facing case management.
 */
@SpringBootApplication(scanBasePackages = ["com.example.usbilling"])
class CaseManagementApplication

fun main(args: Array<String>) {
    runApplication<CaseManagementApplication>(*args)
}
