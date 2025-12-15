package com.example.uspayroll.messaging.events.reporting

import java.time.Instant

/**
 * Reporting/filings oriented paycheck event contract.
 *
 * Design goals:
 * - Stable, long-lived schema using primitives/strings only.
 * - Explicit semantics for paycheck lifecycle actions relevant to ledgering and filings.
 * - Idempotent publication (deterministic eventId) and consumer-side inbox support.
 */
enum class PaycheckLedgerAction {
    COMMITTED,
    VOIDED,
    ADJUSTED,
    REISSUED,
}

data class PaycheckLedgerEvent(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val eventId: String,
    val occurredAt: Instant,

    // Semantics
    val action: PaycheckLedgerAction,

    // Identifiers
    val employerId: String,
    val employeeId: String,
    val payRunId: String,
    val payRunType: String,
    val runSequence: Int,
    val payPeriodId: String,
    val paycheckId: String,

    // Pay period dates (ISO-8601 strings, e.g. "2025-01-15").
    val periodStartIso: String,
    val periodEndIso: String,
    val checkDateIso: String,

    // Correction/reversal linkage.
    val correctionOfPaycheckId: String? = null,
    val correctionOfPayRunId: String? = null,

    // Totals
    val currency: String,
    val grossCents: Long,
    val netCents: Long,

    // Key filing aggregates (mirrors PaycheckAudit stable schema fields).
    val audit: PaycheckLedgerAuditAggregates? = null,

    // Optional line-item detail for reporting/filings.
    val earnings: List<PaycheckLedgerEarningLine> = emptyList(),
    val employeeTaxes: List<PaycheckLedgerTaxLine> = emptyList(),
    val employerTaxes: List<PaycheckLedgerTaxLine> = emptyList(),
    val deductions: List<PaycheckLedgerDeductionLine> = emptyList(),
    val employerContributions: List<PaycheckLedgerEmployerContributionLine> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

data class PaycheckLedgerAuditAggregates(
    val cashGrossCents: Long,
    val grossTaxableCents: Long,
    val federalTaxableCents: Long,
    val stateTaxableCents: Long,
    val socialSecurityWagesCents: Long,
    val medicareWagesCents: Long,
    val supplementalWagesCents: Long,
    val futaWagesCents: Long,
    val employeeTaxCents: Long,
    val employerTaxCents: Long,
    val preTaxDeductionCents: Long,
    val postTaxDeductionCents: Long,
    val garnishmentCents: Long,
)

data class PaycheckLedgerEarningLine(
    val code: String,
    val category: String,
    val description: String,
    val units: Double,
    val rateCents: Long? = null,
    val amountCents: Long,
)

data class PaycheckLedgerTaxLine(
    val ruleId: String,
    val jurisdictionType: String,
    val jurisdictionCode: String,
    val description: String,
    val basisCents: Long,
    val rate: Double? = null,
    val amountCents: Long,
)

data class PaycheckLedgerDeductionLine(
    val code: String,
    val description: String,
    val amountCents: Long,
)

data class PaycheckLedgerEmployerContributionLine(
    val code: String,
    val description: String,
    val amountCents: Long,
)

// end
