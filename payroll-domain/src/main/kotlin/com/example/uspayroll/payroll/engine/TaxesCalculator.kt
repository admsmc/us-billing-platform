package com.example.uspayroll.payroll.engine

import com.example.uspayroll.shared.Money
import com.example.uspayroll.payroll.model.*

/** Result of tax computation for a single paycheck. */
data class TaxComputationResult(
    val employeeTaxes: List<TaxLine>,
    val employerTaxes: List<TaxLine>,
    val traceSteps: List<TraceStep>,
)

/**
 * Simple tax calculator for demonstration:
 * - Uses an explicit map of TaxBasis -> Money (e.g. Gross, FederalTaxable).
 * - Applies employee FlatRateTax rules (federal/state/local) on their configured basis.
 * - Applies employer-specific FlatRateTax rules on their configured basis.
 */
object TaxesCalculator {

    private fun computeBracketedTax(basisMoney: Money, rule: TaxRule.BracketedIncomeTax): Pair<Money, List<BracketApplication>> {
        // Apply standard deduction if present
        val deductionCents = rule.standardDeduction?.amount ?: 0L
        val taxableTotal = (basisMoney.amount - deductionCents).coerceAtLeast(0L)
        if (taxableTotal == 0L) return Money(0L, basisMoney.currency) to emptyList()

        var remaining = taxableTotal
        var previousUpper = 0L
        var totalTaxCents = 0L
        val applications = mutableListOf<BracketApplication>()

        for (bracket in rule.brackets) {
            if (remaining <= 0L) break
            val upper = bracket.upTo?.amount ?: Long.MAX_VALUE
            if (upper <= previousUpper) continue
            val span = upper - previousUpper
            val applied = minOf(remaining, span)
            if (applied > 0L) {
                val taxCents = (applied * bracket.rate.value).toLong()
                totalTaxCents += taxCents
                applications += BracketApplication(
                    bracket = bracket,
                    appliedTo = Money(applied, basisMoney.currency),
                    amount = Money(taxCents, basisMoney.currency),
                )
                remaining -= applied
            }
            previousUpper = upper
        }

        return Money(totalTaxCents, basisMoney.currency) to applications
    }

    fun computeTaxes(
        input: PaycheckInput,
        bases: Map<TaxBasis, Money>,
        basisComponents: Map<TaxBasis, Map<String, Money>>,
    ): TaxComputationResult {
        val traceSteps = mutableListOf<TraceStep>()
        val employeeTaxes = mutableListOf<TaxLine>()
        val employerTaxes = mutableListOf<TaxLine>()

        // Basis steps for each known basis value
        bases.forEach { (basis, amount) ->
            val components = basisComponents[basis] ?: mapOf(
                when (basis) {
                    TaxBasis.Gross -> "gross"
                    TaxBasis.FederalTaxable -> "federalTaxable"
                    TaxBasis.StateTaxable -> "stateTaxable"
                    TaxBasis.SocialSecurityWages -> "socialSecurityWages"
                    TaxBasis.MedicareWages -> "medicareWages"
                    TaxBasis.SupplementalWages -> "supplementalWages"
                } to amount,
            )
            traceSteps += TraceStep.BasisComputed(
                basis = basis,
                components = components,
                result = amount,
            )
        }

        fun applyFlatTax(rule: TaxRule.FlatRateTax, descriptionPrefix: String, target: MutableList<TaxLine>) {
            val basisMoney = bases[rule.basis] ?: return

            val taxableCents: Long = if (rule.annualWageCap != null) {
                val priorWages = input.priorYtd.wagesByBasis[rule.basis]?.amount ?: 0L
                val capCents = rule.annualWageCap.amount
                val remaining = capCents - priorWages
                if (remaining <= 0L) {
                    // Already above the wage base cap; no further tax this period.
                    return
                }
                minOf(basisMoney.amount, remaining)
            } else {
                basisMoney.amount
            }

            var amountCents = (taxableCents * rule.rate.value).toLong()

            // Per-employee extra withholding applies on top of rule-based tax
            val perEmployeeExtra = input.employeeSnapshot.additionalWithholdingPerPeriod
            if (perEmployeeExtra != null) {
                amountCents += perEmployeeExtra.amount
            }
            if (amountCents == 0L) return

            val taxedBasis = Money(taxableCents, basisMoney.currency)
            val taxAmount = Money(amountCents, basisMoney.currency)
            val line = TaxLine(
                ruleId = rule.id,
                jurisdiction = rule.jurisdiction,
                description = "$descriptionPrefix ${rule.jurisdiction.code}",
                basis = taxedBasis,
                rate = rule.rate,
                amount = taxAmount,
            )
            target += line
            traceSteps += TraceStep.TaxApplied(
                ruleId = rule.id,
                jurisdiction = rule.jurisdiction,
                basis = taxedBasis,
                brackets = null,
                rate = rule.rate,
                amount = taxAmount,
            )
            if (perEmployeeExtra != null) {
                traceSteps += TraceStep.AdditionalWithholdingApplied(amount = perEmployeeExtra)
            }
        }

        fun applyBracketedTax(rule: TaxRule.BracketedIncomeTax, descriptionPrefix: String, target: MutableList<TaxLine>) {
            val basisMoney = bases[rule.basis] ?: return
            val (bracketTax, brackets) = computeBracketedTax(basisMoney, rule)

            var totalTaxCents = bracketTax.amount
            val ruleExtra = rule.additionalWithholding
            if (ruleExtra != null) {
                totalTaxCents += ruleExtra.amount
            }
            val perEmployeeExtra = input.employeeSnapshot.additionalWithholdingPerPeriod
            if (perEmployeeExtra != null) {
                totalTaxCents += perEmployeeExtra.amount
            }
            if (totalTaxCents == 0L) return

            val taxAmount = Money(totalTaxCents, basisMoney.currency)
            val line = TaxLine(
                ruleId = rule.id,
                jurisdiction = rule.jurisdiction,
                description = "$descriptionPrefix ${rule.jurisdiction.code}",
                basis = basisMoney,
                rate = null,
                amount = taxAmount,
            )
            target += line
            traceSteps += TraceStep.TaxApplied(
                ruleId = rule.id,
                jurisdiction = rule.jurisdiction,
                basis = basisMoney,
                brackets = brackets,
                rate = null,
                amount = taxAmount,
            )
            if (perEmployeeExtra != null) {
                traceSteps += TraceStep.AdditionalWithholdingApplied(amount = perEmployeeExtra)
            }
        }

        // Treat federal/state/local rules as employee taxes for now
        input.taxContext.federal.forEach { rule ->
            when (rule) {
                is TaxRule.FlatRateTax -> applyFlatTax(rule, "Employee tax", employeeTaxes)
                is TaxRule.BracketedIncomeTax -> applyBracketedTax(rule, "Employee tax", employeeTaxes)
            }
        }
        input.taxContext.state.forEach { rule ->
            when (rule) {
                is TaxRule.FlatRateTax -> applyFlatTax(rule, "Employee tax", employeeTaxes)
                is TaxRule.BracketedIncomeTax -> applyBracketedTax(rule, "Employee tax", employeeTaxes)
            }
        }
        input.taxContext.local.forEach { rule ->
            when (rule) {
                is TaxRule.FlatRateTax -> applyFlatTax(rule, "Employee tax", employeeTaxes)
                is TaxRule.BracketedIncomeTax -> applyBracketedTax(rule, "Employee tax", employeeTaxes)
            }
        }

        // Employer-specific rules are treated as employer taxes
        input.taxContext.employerSpecific.forEach { rule ->
            when (rule) {
                is TaxRule.FlatRateTax -> applyFlatTax(rule, "Employer tax", employerTaxes)
                is TaxRule.BracketedIncomeTax -> applyBracketedTax(rule, "Employer tax", employerTaxes)
            }
        }

        return TaxComputationResult(
            employeeTaxes = employeeTaxes,
            employerTaxes = employerTaxes,
            traceSteps = traceSteps,
        )
    }
}
