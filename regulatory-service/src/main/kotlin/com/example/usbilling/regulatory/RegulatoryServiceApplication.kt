package com.example.usbilling.regulatory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Regulatory Service - manages PUC-mandated charges and jurisdiction-specific rules.
 *
 * This service owns the regulatory compliance bounded context and provides HTTP APIs for:
 * - Regulatory charges (PUC surcharges, riders, mandates)
 * - Jurisdiction-specific rules (state-level regulatory requirements)
 * - Effective-dated charge applicability
 *
 * Implements RegulatoryContextProvider port from regulatory-api.
 *
 * Note: Originally labor-service (payroll domain with FLSA/minimum wage rules).
 * Now adapted for billing with regulatory charges (PCA, DSM, ECA, etc.).
 */
@SpringBootApplication(scanBasePackages = ["com.example.usbilling"])
class RegulatoryServiceApplication

fun main(args: Array<String>) {
    runApplication<RegulatoryServiceApplication>(*args)
}
