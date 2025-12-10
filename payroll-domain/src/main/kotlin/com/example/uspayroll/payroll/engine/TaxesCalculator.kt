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
                    TaxBasis.FutaWages -> "futaWages"
                } to amount,
            )
            traceSteps += TraceStep.BasisComputed(
                basis = basis,
                components = components,
                result = amount,
            )
        }

        fun shouldSkipFicaOrMedicare(
            basis: TaxBasis,
            basisMoney: Money,
        ): Boolean {
            val snapshot = input.employeeSnapshot
            val employmentType = snapshot.employmentType

            val isFicaBasis = basis is TaxBasis.SocialSecurityWages || basis is TaxBasis.MedicareWages
            if (!isFicaBasis) return false

            // Global FICA exemption flag.
            if (snapshot.ficaExempt) return true

            // Special thresholds for household and election workers; below
            // these amounts in the year, FICA/Medicare should not apply.
            val thresholdCents: Long? = when (employmentType) {
                com.example.uspayroll.payroll.model.EmploymentType.HOUSEHOLD -> 2_800_00L
                com.example.uspayroll.payroll.model.EmploymentType.ELECTION_WORKER -> 2_400_00L
                else -> null
            }

            if (thresholdCents != null) {
                val priorWages = input.priorYtd.wagesByBasis[basis]?.amount ?: 0L
                if (priorWages + basisMoney.amount < thresholdCents) {
                    return true
                }
            }

            return false
        }

        fun applyFlatTax(rule: TaxRule.FlatRateTax, descriptionPrefix: String, target: MutableList<TaxLine>) {
            val basisMoney = bases[rule.basis] ?: return

            if (shouldSkipFicaOrMedicare(rule.basis, basisMoney)) {
                return
            }

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
            if (shouldSkipFicaOrMedicare(rule.basis, basisMoney)) {
                return
            }

            // Special handling for Additional Medicare: apply the 0.9% rate
            // only to wages in this period that exceed the annual threshold
            // when combined with prior-year-to-date Medicare wages for this
            // employer, per IRS rules.
            if (rule.id == "US_FED_ADDITIONAL_MEDICARE_2025" && rule.basis is TaxBasis.MedicareWages) {
                val priorYtd = input.priorYtd.wagesByBasis[TaxBasis.MedicareWages]?.amount ?: 0L
                val current = basisMoney.amount
                val addlTaxCents = FicaPolicy.additionalMedicareForPeriod(
                    priorMedicareYtdCents = priorYtd,
                    currentMedicareCents = current,
                )
                if (addlTaxCents <= 0L) {
                    return
                }
                val taxAmount = Money(addlTaxCents, basisMoney.currency)
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
                    brackets = emptyList(),
                    rate = null,
                    amount = taxAmount,
                )
                return
            }

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

        fun applyWageBracketTax(rule: TaxRule.WageBracketTax, descriptionPrefix: String, target: MutableList<TaxLine>) {
            val basisMoney = bases[rule.basis] ?: return
            if (shouldSkipFicaOrMedicare(rule.basis, basisMoney)) {
                return
            }

            // Find the first bracket whose upper bound contains the basis amount.
            val amount = basisMoney.amount
            val row = rule.brackets.firstOrNull { bracket ->
                val upper = bracket.upTo?.amount ?: Long.MAX_VALUE
                amount <= upper
            } ?: return

            var totalTaxCents = row.tax.amount
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
                brackets = emptyList(),
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
                is TaxRule.WageBracketTax -> applyWageBracketTax(rule, "Employee tax", employeeTaxes)
            }
        }
        input.taxContext.state.forEach { rule ->
            when (rule) {
                is TaxRule.FlatRateTax -> applyFlatTax(rule, "Employee tax", employeeTaxes)
                is TaxRule.BracketedIncomeTax -> applyBracketedTax(rule, "Employee tax", employeeTaxes)
                is TaxRule.WageBracketTax -> applyWageBracketTax(rule, "Employee tax", employeeTaxes)
            }
        }
        input.taxContext.local.forEach { rule ->
            when (rule) {
                is TaxRule.FlatRateTax -> applyFlatTax(rule, "Employee tax", employeeTaxes)
                is TaxRule.BracketedIncomeTax -> applyBracketedTax(rule, "Employee tax", employeeTaxes)
                is TaxRule.WageBracketTax -> applyWageBracketTax(rule, "Employee tax", employeeTaxes)
            }
        }

        // Employer-specific rules are treated as employer taxes
        input.taxContext.employerSpecific.forEach { rule ->
            when (rule) {
                is TaxRule.FlatRateTax -> applyFlatTax(rule, "Employer tax", employerTaxes)
                is TaxRule.BracketedIncomeTax -> applyBracketedTax(rule, "Employer tax", employerTaxes)
                is TaxRule.WageBracketTax -> applyWageBracketTax(rule, "Employer tax", employerTaxes)
            }
        }

        return TaxComputationResult(
            employeeTaxes = employeeTaxes,
            employerTaxes = employerTaxes,
            traceSteps = traceSteps,
        )
    }
}
