package com.example.usbilling.worker

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Billing Worker Service - message-driven bill computation engine.
 *
 * This service processes billing requests by:
 * 1. Consuming BillComputationRequested messages from queue
 * 2. Calling customer-service, rate-service, regulatory-service via HTTP
 * 3. Running BillingEngine.calculateBill() with assembled context
 * 4. Publishing BillComputationCompleted messages with results
 *
 * Provides a demo endpoint for synchronous dry-run bill calculations.
 */
@SpringBootApplication(scanBasePackages = ["com.example.usbilling"])
class BillingWorkerApplication

fun main(args: Array<String>) {
    runApplication<BillingWorkerApplication>(*args)
}
