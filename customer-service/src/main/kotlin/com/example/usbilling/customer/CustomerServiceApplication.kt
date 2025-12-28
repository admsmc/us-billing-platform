package com.example.usbilling.customer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Customer Service - manages customer data, meters, billing periods, and meter reads.
 *
 * This service owns the customer bounded context and provides HTTP APIs for:
 * - Customer snapshots (profile, service address, customer class)
 * - Billing periods (cycle windows with start/end dates)
 * - Meter installations and meter reads
 *
 * Implements CustomerSnapshotProvider and BillingPeriodProvider ports from customer-api.
 */
@SpringBootApplication(scanBasePackages = ["com.example.usbilling"])
class CustomerServiceApplication

fun main(args: Array<String>) {
    runApplication<CustomerServiceApplication>(*args)
}
