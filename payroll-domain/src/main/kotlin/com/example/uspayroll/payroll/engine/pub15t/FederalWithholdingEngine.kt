package com.example.uspayroll.payroll.engine.pub15t

import com.example.uspayroll.payroll.engine.BasisComputation
import com.example.uspayroll.payroll.model.FilingStatus
import com.example.uspayroll.payroll.model.PayFrequency
import com.example.uspayroll.payroll.model.PaycheckInput
import com.example.uspayroll.payroll.model.TaxRule
import com.example.uspayroll.payroll.model.TaxBasis
import com.example.uspayroll.payroll.model.TaxContext
import com.example.uspayroll.payroll.model.TraceStep
import com.example.uspayroll.shared.Money

/**
 * High-level selector for Pub. 15-T style federal income tax withholding.
 *
 * This engine is designed as a pure function over immutable inputs. A thin
 * adapter in tax-service will be responsible for fetching TaxContext,
 * computing bases via BasisBuilder, and constructing a WithholdingProfile.
 */
object FederalWithholdingEngine {

    enum class WithholdingMethod {
        /** Percentage method for automated payroll systems (Pub. 15-T Worksheet 1A). */
        PERCENTAGE,
        /** Wage-bracket method tables where available. */
        WAGE_BRACKET,
    }

    data class FederalWithholdingResult(
        val amount: Money,
        val trace: List<TraceStep> = emptyList(),
    )

    /**
     * Entry point for computing federal income tax withholding for a single
     * paycheck. For now this is a stub that returns zero; later phases will
     * implement the full Pub. 15-T logic.
     */
    fun computeWithholding(
        input: PaycheckInput,
        bases: BasisComputation,
        profile: WithholdingProfile,
        federalRules: List<TaxRule>,
        method: WithholdingMethod,
    ): FederalWithholdingResult {
        // For now we only select which rule would be applied and emit a trace
        // note; the actual tax computation is implemented in later phases.
        val trace = mutableListOf<TraceStep>()

        // Derive per-period NRA adjustment (if applicable) for trace purposes.
        val nraExtra = if (profile.isNonresidentAlien) {
            val firstPaidBefore2020 = profile.firstPaidBefore2020 ?: false
            NraAdjustment.extraWagesForNra(
                frequency = input.period.frequency,
                w4Version = profile.w4Version,
                firstPaidBefore2020 = firstPaidBefore2020,
            ).also { extra ->
                trace += TraceStep.Note(
                    "Applied NRA extra wages of ${extra.amount} cents for frequency=${input.period.frequency} w4Version=${profile.w4Version} firstPaidBefore2020=$firstPaidBefore2020",
                )
            }
        } else {
            Money(0L)
        }

        var withholdingCents = 0L

        when (method) {
            WithholdingMethod.PERCENTAGE -> {
                val rule = selectBracketedFitRule(federalRules, profile)
                if (rule != null) {
                    val (taxCents, pctTrace) = computePercentageMethodWithholding(
                        input = input,
                        bases = bases,
                        profile = profile,
                        fitRule = rule,
                        nraExtra = nraExtra,
                    )
                    withholdingCents = taxCents
                    trace += pctTrace
                } else {
                    trace += TraceStep.Note(
                        "No percentage-method FIT rule found for filingStatus=${profile.filingStatus} step2MultipleJobs=${profile.step2MultipleJobs}")
                }
            }
            WithholdingMethod.WAGE_BRACKET -> {
                val rule = selectWageBracketFitRule(federalRules, profile)
                if (rule != null) {
                    val (taxCents, wbTrace) = computeWageBracketWithholding(
                        input = input,
                        bases = bases,
                        profile = profile,
                        fitRule = rule,
                        nraExtra = nraExtra,
                    )
                    withholdingCents = taxCents
                    trace += wbTrace
                } else {
                    trace += TraceStep.Note(
                        "No wage-bracket FIT rule found for filingStatus=${profile.filingStatus} step2MultipleJobs=${profile.step2MultipleJobs}",
                    )
                }
            }
        }

        return FederalWithholdingResult(amount = Money(withholdingCents), trace = trace)
    }

    private fun computePercentageMethodWithholding(
        input: PaycheckInput,
        bases: BasisComputation,
        profile: WithholdingProfile,
        fitRule: TaxRule.BracketedIncomeTax,
        nraExtra: Money,
    ): Pair<Long, List<TraceStep>> {
        val trace = mutableListOf<TraceStep>()

        val periodBasis = bases.bases[TaxBasis.FederalTaxable]
            ?: Money(0L)

        // Add NRA extra wages to per-period basis if applicable.
        val adjustedPeriodCents = (periodBasis.amount + nraExtra.amount)
            .coerceAtLeast(0L)
        val adjustedPeriod = Money(adjustedPeriodCents, periodBasis.currency)

        val periodsPerYear = when (input.period.frequency) {
            PayFrequency.WEEKLY -> 52L
            PayFrequency.BIWEEKLY -> 26L
            PayFrequency.FOUR_WEEKLY -> 13L
            PayFrequency.SEMI_MONTHLY -> 24L
            PayFrequency.MONTHLY -> 12L
            PayFrequency.QUARTERLY -> 4L
            PayFrequency.ANNUAL -> 1L
        }

        val baseAnnualWages = adjustedPeriodCents * periodsPerYear

        val otherIncome = profile.step4OtherIncomeAnnual?.amount ?: 0L
        val deductions = profile.step4DeductionsAnnual?.amount ?: 0L
        val adjustedAnnualWages = (baseAnnualWages + otherIncome - deductions)
            .coerceAtLeast(0L)

        trace += TraceStep.Note(
            "Annualized FederalTaxable wages = $baseAnnualWages, other income = $otherIncome, deductions = $deductions, adjusted = $adjustedAnnualWages",
        )

        // Build a synthetic TaxContext and bases map for TaxesCalculator.
        val taxContext = TaxContext(federal = listOf(fitRule))
        val basesMap: Map<TaxBasis, Money> = mapOf(
            TaxBasis.FederalTaxable to Money(adjustedAnnualWages, adjustedPeriod.currency),
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.FederalTaxable to mapOf("federalTaxable" to Money(adjustedAnnualWages, adjustedPeriod.currency)),
        )

        val annualPeriod = input.period.copy(frequency = PayFrequency.ANNUAL)
        val annualSnapshot = input.employeeSnapshot.copy(
            // Avoid double-applying per-period extra withholding inside TaxesCalculator;
            // the engine will add Step 4(c) amounts explicitly after de-annualizing.
            additionalWithholdingPerPeriod = null,
        )
        val annualInput = input.copy(
            period = annualPeriod,
            employeeSnapshot = annualSnapshot,
            taxContext = taxContext,
        )

        val result = com.example.uspayroll.payroll.engine.TaxesCalculator.computeTaxes(
            input = annualInput,
            bases = basesMap,
            basisComponents = basisComponents,
        )

        val fitLine = result.employeeTaxes.firstOrNull { it.ruleId == fitRule.id }
        if (fitLine == null) {
            trace += TraceStep.Note("No annual FIT tax line produced for ruleId=${fitRule.id}")
            return 0L to trace
        }

        val annualTaxCents = fitLine.amount.amount
        val creditCents = profile.step3AnnualCredit?.amount ?: 0L
        val netAnnualTaxCents = (annualTaxCents - creditCents).coerceAtLeast(0L)

        trace += TraceStep.Note("Annual FIT before credits = $annualTaxCents, credits = $creditCents, net = $netAnnualTaxCents")

        val perPeriodTaxCents = (netAnnualTaxCents / periodsPerYear).coerceAtLeast(0L)
        val extraPerPeriod = profile.extraWithholdingPerPeriod?.amount ?: 0L
        val totalPerPeriodCents = perPeriodTaxCents + extraPerPeriod

        trace += TraceStep.Note(
            "Per-period FIT = $perPeriodTaxCents, extra withholding = $extraPerPeriod, total = $totalPerPeriodCents",
        )

        return totalPerPeriodCents to trace
    }

    private fun selectBracketedFitRule(
        federalRules: List<TaxRule>,
        profile: WithholdingProfile,
    ): TaxRule.BracketedIncomeTax? {
        val all = federalRules.filterIsInstance<TaxRule.BracketedIncomeTax>()
        val byStatus = all.filter { rule ->
            val fs = rule.filingStatus
            fs == null || fs == profile.filingStatus
        }
        if (byStatus.isEmpty()) return null

        val wantsStep2 = profile.step2MultipleJobs

        fun isStep2Id(id: String): Boolean = id.contains("STEP2", ignoreCase = true)

        return if (wantsStep2) {
            byStatus.firstOrNull { isStep2Id(it.id) } ?: byStatus.first()
        } else {
            byStatus.firstOrNull { !isStep2Id(it.id) } ?: byStatus.first()
        }
    }

    private fun selectWageBracketFitRule(
        federalRules: List<TaxRule>,
        profile: WithholdingProfile,
    ): TaxRule.WageBracketTax? {
        val all = federalRules.filterIsInstance<TaxRule.WageBracketTax>()
        val byStatus = all.filter { rule ->
            val fs = rule.filingStatus
            fs == null || fs == profile.filingStatus
        }
        if (byStatus.isEmpty()) return null

        val wantsStep2 = profile.step2MultipleJobs

        fun isStep2Id(id: String): Boolean = id.contains("STEP2", ignoreCase = true)

        return if (wantsStep2) {
            byStatus.firstOrNull { isStep2Id(it.id) } ?: byStatus.first()
        } else {
            byStatus.firstOrNull { !isStep2Id(it.id) } ?: byStatus.first()
        }
    }

    private fun computeWageBracketWithholding(
        input: PaycheckInput,
        bases: BasisComputation,
        profile: WithholdingProfile,
        fitRule: TaxRule.WageBracketTax,
        nraExtra: Money,
    ): Pair<Long, List<TraceStep>> {
        val trace = mutableListOf<TraceStep>()

        val periodBasis = bases.bases[TaxBasis.FederalTaxable]
            ?: Money(0L)

        // Add NRA extra wages to per-period basis if applicable.
        val adjustedPeriodCents = (periodBasis.amount + nraExtra.amount)
            .coerceAtLeast(0L)
        val adjustedPeriod = Money(adjustedPeriodCents, periodBasis.currency)

        trace += TraceStep.Note(
            "Wage-bracket FederalTaxable wages for period = ${adjustedPeriod.amount} cents",
        )

        // Build a synthetic TaxContext and bases map for TaxesCalculator.
        val taxContext = TaxContext(federal = listOf(fitRule))
        val basesMap: Map<TaxBasis, Money> = mapOf(
            TaxBasis.FederalTaxable to adjustedPeriod,
        )
        val basisComponents: Map<TaxBasis, Map<String, Money>> = mapOf(
            TaxBasis.FederalTaxable to mapOf("federalTaxable" to adjustedPeriod),
        )

        val snapshotWithoutExtra = input.employeeSnapshot.copy(
            // Avoid double-applying per-period extra withholding inside TaxesCalculator;
            // the engine will add Step 4(c) amounts explicitly below.
            additionalWithholdingPerPeriod = null,
        )
        val wageInput = input.copy(
            employeeSnapshot = snapshotWithoutExtra,
            taxContext = taxContext,
        )

        val result = com.example.uspayroll.payroll.engine.TaxesCalculator.computeTaxes(
            input = wageInput,
            bases = basesMap,
            basisComponents = basisComponents,
        )

        val fitLine = result.employeeTaxes.firstOrNull { it.ruleId == fitRule.id }
        if (fitLine == null) {
            trace += TraceStep.Note("No wage-bracket FIT tax line produced for ruleId=${fitRule.id}")
            return 0L to trace
        }

        val basePerPeriodTaxCents = fitLine.amount.amount.coerceAtLeast(0L)
        val extraPerPeriod = profile.extraWithholdingPerPeriod?.amount ?: 0L
        val totalPerPeriodCents = basePerPeriodTaxCents + extraPerPeriod

        trace += TraceStep.Note(
            "Wage-bracket FIT base = $basePerPeriodTaxCents, extra withholding = $extraPerPeriod, total = $totalPerPeriodCents",
        )

        return totalPerPeriodCents to trace
    }
}
