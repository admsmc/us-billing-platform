package com.example.usbilling.orchestrator

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Billing Orchestrator Service - manages bill lifecycle and persistence.
 *
 * This service orchestrates the billing process by:
 * 1. Managing billing cycles and triggering bill generation
 * 2. Persisting bills and bill lines to database
 * 3. Managing bill status state machine (DRAFT → COMPUTING → FINALIZED → ISSUED)
 * 4. Publishing bill events (BillFinalized, BillVoided, etc.)
 * 5. Supporting void/rebill operations
 *
 * Provides REST APIs for bill management and lifecycle operations.
 */
@SpringBootApplication(scanBasePackages = ["com.example.usbilling"])
class BillingOrchestratorApplication

fun main(args: Array<String>) {
    runApplication<BillingOrchestratorApplication>(*args)
}
