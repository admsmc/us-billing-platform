package com.example.usbilling.payroll.model

import com.example.usbilling.payroll.model.config.DeductionEffect
import com.example.usbilling.shared.Money

// Calculation trace

sealed class TraceStep {
    data class BasisComputed(
        val basis: TaxBasis,
        val components: Map<String, Money>,
        val result: Money,
    ) : TraceStep()

    data class TaxApplied(
        val ruleId: String,
        val jurisdiction: TaxJurisdiction,
        val basis: Money,
        val brackets: List<BracketApplication>?,
        val rate: Percent?,
        val amount: Money,
    ) : TraceStep()

    data class DeductionApplied(
        val description: String,
        val basis: Money,
        val rate: Percent?,
        val amount: Money,
        val cappedAt: Money?,
        val effects: Set<DeductionEffect>,
    ) : TraceStep()

    data class ProrationApplied(
        val strategy: String,
        val explicitOverride: Boolean,
        val fraction: Double,
        val fullCents: Long,
        val appliedCents: Long,
    ) : TraceStep()

    data class AdditionalWithholdingApplied(
        val amount: Money,
    ) : TraceStep()

    /**
     * Indicates that a protected earnings floor reduced a requested
     * garnishment amount for a specific order.
     */
    data class ProtectedEarningsApplied(
        val orderId: String,
        val requestedCents: Long,
        val adjustedCents: Long,
        val floorCents: Long,
    ) : TraceStep()

    /**
     * Garnishment-specific event capturing the full context of an applied order.
     */
    data class GarnishmentApplied(
        val orderId: String,
        val type: String,
        val description: String,
        val requestedCents: Long,
        val appliedCents: Long,
        val disposableBeforeCents: Long,
        val disposableAfterCents: Long,
        val protectedEarningsFloorCents: Long?,
        val protectedFloorConstrained: Boolean,
        val arrearsBeforeCents: Long?,
        val arrearsAfterCents: Long?,
        /** Portion of [appliedCents] treated as current support for this period. */
        val appliedToCurrentCents: Long? = null,
        /** Portion of [appliedCents] applied to arrears principal. */
        val appliedToArrearsCents: Long? = null,
    ) : TraceStep()

    /**
     * Snapshot of how disposable income was computed for a given order.
     */
    data class DisposableIncomeComputed(
        val orderId: String,
        val grossCents: Long,
        val mandatoryPreTaxCents: Long,
        val employeeTaxCents: Long,
        val baseDisposableCents: Long,
        val netForProtectedFloorCents: Long,
    ) : TraceStep()

    /**
     * Indicates that a support (child/spousal) cap constrained the total
     * support withholding for a paycheck.
     */
    data class SupportCapApplied(
        val jurisdictionCode: String?,
        val ccpaCapCents: Long,
        val stateCapCents: Long?,
        val effectiveCapCents: Long,
        val totalRequestedCents: Long,
        val totalAppliedCents: Long,
    ) : TraceStep()

    data class Note(val message: String) : TraceStep()
}

data class BracketApplication(
    val bracket: TaxBracket,
    val appliedTo: Money,
    val amount: Money,
)

data class CalculationTrace(
    val steps: List<TraceStep> = emptyList(),
)
