package com.example.uspayroll.payroll.model.audit

import java.time.Instant
import java.time.LocalDate

/**
 * Stable-schema, persistable audit record for an authoritative (committed) paycheck calculation.
 *
 * Design goals:
 * - Deterministic and reproducible.
 * - Avoid polymorphic/"sealed" payloads.
 * - Uses primitives/strings for long-term storage stability.
 *
 * Notes:
 * - [computedAt] must be supplied by a service boundary (no system clock access inside the payroll domain).
 * - Fingerprints should prefer immutable version IDs; otherwise use sha256 hashes; otherwise use explicit "UNKNOWN".
 */
data class PaycheckAudit(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val engineVersion: String,
    val computedAt: Instant,

    // Identifiers
    val employerId: String,
    val employeeId: String,
    val paycheckId: String,
    val payRunId: String?,
    val payPeriodId: String,
    val checkDate: LocalDate,

    // Input fingerprints
    val employeeSnapshotFingerprint: InputFingerprint = InputFingerprint.UNKNOWN,
    val taxContextFingerprint: InputFingerprint = InputFingerprint.UNKNOWN,
    val laborStandardsFingerprint: InputFingerprint = InputFingerprint.UNKNOWN,
    val earningConfigFingerprint: InputFingerprint = InputFingerprint.UNKNOWN,
    val deductionConfigFingerprint: InputFingerprint = InputFingerprint.UNKNOWN,

    // Applied selectors (stable IDs)
    val appliedTaxRuleIds: List<String> = emptyList(),
    val appliedDeductionPlanIds: List<String> = emptyList(),
    val appliedGarnishmentOrderIds: List<String> = emptyList(),

    // Key aggregates
    val cashGrossCents: Long,
    val grossTaxableCents: Long,
    val federalTaxableCents: Long,
    val stateTaxableCents: Long,
    val socialSecurityWagesCents: Long,
    val medicareWagesCents: Long,
    val supplementalWagesCents: Long,
    val futaWagesCents: Long,

    // Totals
    val employeeTaxCents: Long,
    val employerTaxCents: Long,
    val preTaxDeductionCents: Long,
    val postTaxDeductionCents: Long,
    val garnishmentCents: Long,
    val netCents: Long,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * Version/hash fingerprint used to support deterministic replay.
 *
 * Enterprise policy:
 * - Prefer stable version IDs.
 * - Otherwise persist sha256 of a canonical serialization.
 * - Otherwise persist explicit "UNKNOWN" (never silently null).
 */
data class InputFingerprint(
    val version: String = UNKNOWN_VALUE,
    val sha256: String = UNKNOWN_VALUE,
) {
    companion object {
        const val UNKNOWN_VALUE: String = "UNKNOWN"
        val UNKNOWN: InputFingerprint = InputFingerprint()
    }
}
