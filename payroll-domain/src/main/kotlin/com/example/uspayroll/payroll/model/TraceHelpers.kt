package com.example.uspayroll.payroll.model

/**
 * Summary view of proration applied to a salaried paycheck, derived from trace.
 */
data class ProrationSummary(
    val strategy: String,
    val explicitOverride: Boolean,
    val fraction: Double,
    val fullCents: Long,
    val appliedCents: Long,
)

/**
 * Extract the first proration summary (if any) from a CalculationTrace.
 */
fun CalculationTrace.prorationSummary(): ProrationSummary? =
    steps.filterIsInstance<TraceStep.ProrationApplied>()
        .firstOrNull()
        ?.let { s ->
            ProrationSummary(
                strategy = s.strategy,
                explicitOverride = s.explicitOverride,
                fraction = s.fraction,
                fullCents = s.fullCents,
                appliedCents = s.appliedCents,
            )
        }

/**
 * Convenience helper to access proration summary directly from a PaycheckResult.
 */
fun PaycheckResult.prorationSummary(): ProrationSummary? =
    trace.prorationSummary()
