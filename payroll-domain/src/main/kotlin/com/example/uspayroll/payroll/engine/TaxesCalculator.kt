package com.example.uspayroll.payroll.engine

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.Money

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

        val applications = ArrayList<BracketApplication>(rule.brackets.size)

        for (bracket in rule.brackets) {
            if (remaining <= 0L) break

            val upper = bracket.upTo?.amount ?: Long.MAX_VALUE
            if (upper <= previousUpper) continue

            val span = upper - previousUpper
            val applied = minOf(remaining, span)
            if (applied <= 0L) {
                previousUpper = upper
                continue
            }

            val taxCents = (applied * bracket.rate.value).toLong()
            totalTaxCents += taxCents

            applications.add(
                BracketApplication(
                    bracket = bracket,
                    appliedTo = Money(applied, basisMoney.currency),
                    amount = Money(taxCents, basisMoney.currency),
                ),
            )

            remaining -= applied
            previousUpper = upper
        }

        return Money(totalTaxCents, basisMoney.currency) to applications
    }

    fun computeTaxes(input: PaycheckInput, bases: Map<TaxBasis, Money>, basisComponents: Map<TaxBasis, Map<String, Money>>, includeTrace: Boolean = true): TaxComputationResult {
        // Basis steps for each known basis value (debug trace only)
        val basisTraceSteps: List<TraceStep> = if (includeTrace) {
            bases.map { (basis, amount) ->
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
                TraceStep.BasisComputed(
                    basis = basis,
                    components = components,
                    result = amount,
                )
            }
        } else {
            emptyList()
        }

        fun shouldSkipFicaOrMedicare(basis: TaxBasis, basisMoney: Money): Boolean {
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

        fun applyFlatTax(rule: TaxRule.FlatRateTax, descriptionPrefix: String, target: MutableList<TaxLine>, trace: MutableList<TraceStep>?) {
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

            val perEmployeeExtra = input.employeeSnapshot.additionalWithholdingPerPeriod
            val amountCents = (taxableCents * rule.rate.value).toLong() + (perEmployeeExtra?.amount ?: 0L)
            if (amountCents == 0L) return

            val taxedBasis = Money(taxableCents, basisMoney.currency)
            val taxAmount = Money(amountCents, basisMoney.currency)

            target.add(
                TaxLine(
                    ruleId = rule.id,
                    jurisdiction = rule.jurisdiction,
                    description = "$descriptionPrefix ${rule.jurisdiction.code}",
                    basis = taxedBasis,
                    rate = rule.rate,
                    amount = taxAmount,
                ),
            )

            if (trace != null) {
                trace.add(
                    TraceStep.TaxApplied(
                        ruleId = rule.id,
                        jurisdiction = rule.jurisdiction,
                        basis = taxedBasis,
                        brackets = null,
                        rate = rule.rate,
                        amount = taxAmount,
                    ),
                )
                if (perEmployeeExtra != null) {
                    trace.add(TraceStep.AdditionalWithholdingApplied(amount = perEmployeeExtra))
                }
            }
        }

        fun applyBracketedTax(rule: TaxRule.BracketedIncomeTax, descriptionPrefix: String, target: MutableList<TaxLine>, trace: MutableList<TraceStep>?) {
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
                target.add(
                    TaxLine(
                        ruleId = rule.id,
                        jurisdiction = rule.jurisdiction,
                        description = "$descriptionPrefix ${rule.jurisdiction.code}",
                        basis = basisMoney,
                        rate = null,
                        amount = taxAmount,
                    ),
                )
                trace?.add(
                    TraceStep.TaxApplied(
                        ruleId = rule.id,
                        jurisdiction = rule.jurisdiction,
                        basis = basisMoney,
                        brackets = emptyList(),
                        rate = null,
                        amount = taxAmount,
                    ),
                )
                return
            }

            val (bracketTax, brackets) = computeBracketedTax(basisMoney, rule)

            val ruleExtra = rule.additionalWithholding
            val perEmployeeExtra = input.employeeSnapshot.additionalWithholdingPerPeriod

            val totalTaxCents = bracketTax.amount +
                (ruleExtra?.amount ?: 0L) +
                (perEmployeeExtra?.amount ?: 0L)

            if (totalTaxCents == 0L) return

            val taxAmount = Money(totalTaxCents, basisMoney.currency)
            target.add(
                TaxLine(
                    ruleId = rule.id,
                    jurisdiction = rule.jurisdiction,
                    description = "$descriptionPrefix ${rule.jurisdiction.code}",
                    basis = basisMoney,
                    rate = null,
                    amount = taxAmount,
                ),
            )
            if (trace != null) {
                trace.add(
                    TraceStep.TaxApplied(
                        ruleId = rule.id,
                        jurisdiction = rule.jurisdiction,
                        basis = basisMoney,
                        brackets = brackets,
                        rate = null,
                        amount = taxAmount,
                    ),
                )
                if (perEmployeeExtra != null) {
                    trace.add(TraceStep.AdditionalWithholdingApplied(amount = perEmployeeExtra))
                }
            }
        }

        fun applyWageBracketTax(rule: TaxRule.WageBracketTax, descriptionPrefix: String, target: MutableList<TaxLine>, trace: MutableList<TraceStep>?) {
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

            val perEmployeeExtra = input.employeeSnapshot.additionalWithholdingPerPeriod
            val totalTaxCents = row.tax.amount + (perEmployeeExtra?.amount ?: 0L)
            if (totalTaxCents == 0L) return

            val taxAmount = Money(totalTaxCents, basisMoney.currency)
            target.add(
                TaxLine(
                    ruleId = rule.id,
                    jurisdiction = rule.jurisdiction,
                    description = "$descriptionPrefix ${rule.jurisdiction.code}",
                    basis = basisMoney,
                    rate = null,
                    amount = taxAmount,
                ),
            )
            if (trace != null) {
                trace.add(
                    TraceStep.TaxApplied(
                        ruleId = rule.id,
                        jurisdiction = rule.jurisdiction,
                        basis = basisMoney,
                        brackets = emptyList(),
                        rate = null,
                        amount = taxAmount,
                    ),
                )
                if (perEmployeeExtra != null) {
                    trace.add(TraceStep.AdditionalWithholdingApplied(amount = perEmployeeExtra))
                }
            }
        }

        val employeeRules = input.taxContext.federal + input.taxContext.state + input.taxContext.local

        val employeeTaxes = ArrayList<TaxLine>(employeeRules.size)
        val employerTaxes = ArrayList<TaxLine>(input.taxContext.employerSpecific.size)
        val traceSteps: MutableList<TraceStep>? = if (includeTrace) {
            ArrayList<TraceStep>(basisTraceSteps.size + employeeRules.size + input.taxContext.employerSpecific.size).apply {
                addAll(basisTraceSteps)
            }
        } else {
            null
        }

        for (rule in employeeRules) {
            when (rule) {
                is TaxRule.FlatRateTax -> applyFlatTax(rule, "Employee tax", employeeTaxes, traceSteps)
                is TaxRule.BracketedIncomeTax -> applyBracketedTax(rule, "Employee tax", employeeTaxes, traceSteps)
                is TaxRule.WageBracketTax -> applyWageBracketTax(rule, "Employee tax", employeeTaxes, traceSteps)
            }
        }

        for (rule in input.taxContext.employerSpecific) {
            when (rule) {
                is TaxRule.FlatRateTax -> applyFlatTax(rule, "Employer tax", employerTaxes, traceSteps)
                is TaxRule.BracketedIncomeTax -> applyBracketedTax(rule, "Employer tax", employerTaxes, traceSteps)
                is TaxRule.WageBracketTax -> applyWageBracketTax(rule, "Employer tax", employerTaxes, traceSteps)
            }
        }

        return TaxComputationResult(
            employeeTaxes = employeeTaxes,
            employerTaxes = employerTaxes,
            traceSteps = traceSteps ?: emptyList(),
        )
    }
}
