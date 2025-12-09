package com.example.uspayroll.payroll.model

import com.example.uspayroll.shared.Money
import com.example.uspayroll.payroll.model.config.DeductionEffect

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
