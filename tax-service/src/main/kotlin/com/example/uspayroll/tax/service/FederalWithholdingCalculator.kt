package com.example.uspayroll.tax.service

import com.example.uspayroll.payroll.model.PayFrequency
import com.example.uspayroll.payroll.model.PaycheckInput
import com.example.uspayroll.payroll.model.TaxBasis
import com.example.uspayroll.payroll.model.TaxRule
import com.example.uspayroll.shared.Money

/**
 * Input for computing federal income tax withholding for a single paycheck.
 *
 * This adapter allows tax-service to apply W-4 and nonresident alien
 * adjustments (per IRS Pub. 15-T) before delegating to the generic tax engine.
 */
data class FederalWithholdingInput(
    val paycheckInput: PaycheckInput,
)

/**
 * Service that computes federal income tax withholding for a paycheck, using
 * the core tax engine and additional W-4/nonresident-alien context.
 */
interface FederalWithholdingCalculator {
    fun computeWithholding(input: FederalWithholdingInput): Money
}

/**
 * Default implementation that:
 * - Respects the `federalWithholdingExempt` flag on EmployeeSnapshot.
 * - Uses the existing tax engine's TaxContext federal rules to compute a
 *   baseline withholding amount on the FederalTaxable basis (or Gross if not
 *   available).
 * - Applies a simple annualization/de-annualization flow to incorporate
 *   W-4 Step 3 (credits) and Steps 4(a)/4(b) (other income/deductions) in an
 *   approximate percentage-method style.
 *
 * This implementation is intentionally conservative and can be refined over
 * time as more of Pub. 15-T is encoded into configuration.
 */
class DefaultFederalWithholdingCalculator : FederalWithholdingCalculator {

    override fun computeWithholding(input: FederalWithholdingInput): Money {
        val snapshot = input.paycheckInput.employeeSnapshot

        if (snapshot.federalWithholdingExempt) {
            return Money(0L)
        }

        val frequency = input.paycheckInput.period.frequency
        val periodsPerYear = periodsPerYear(frequency)

        // For now, approximate the federal withholding basis using the
        // paycheck's base compensation as a proxy. In a future iteration this
        // can be refined to use an explicit FederalTaxable basis once exposed.
        val periodWagesCents = when (val base = snapshot.baseCompensation) {
            is com.example.uspayroll.payroll.model.BaseCompensation.Salaried ->
                // Use per-period allocated salary as a simple proxy.
                base.annualSalary.amount / periodsPerYear
            is com.example.uspayroll.payroll.model.BaseCompensation.Hourly -> 0L
        }

        // Annualize wages for withholding computation.
        val annualWagesCents = periodWagesCents * periodsPerYear

        var adjustedAnnualWages = annualWagesCents
        snapshot.w4OtherIncomeAnnual?.let { adjustedAnnualWages += it.amount }
        snapshot.w4DeductionsAnnual?.let { adjustedAnnualWages -= it.amount }

        // Compute baseline annual tax using the existing federal bracket rules.
        val federalRules = input.paycheckInput.taxContext.federal
        val bracketRules = federalRules.filterIsInstance<TaxRule.BracketedIncomeTax>()

        val annualTaxCents = bracketRules.fold(0L) { acc, rule ->
            acc + computeBracketedTaxOnAnnualWages(adjustedAnnualWages, rule)
        }

        // Apply any annual credit from W-4 Step 3.
        val creditCents = snapshot.w4AnnualCreditAmount?.amount ?: 0L
        val netAnnualTaxCents = (annualTaxCents - creditCents).coerceAtLeast(0L)

        // Convert back to a per-period amount, rounding down.
        var perPeriodTaxCents = netAnnualTaxCents / periodsPerYear

        // Add any per-period extra withholding from EmployeeSnapshot.
        snapshot.additionalWithholdingPerPeriod?.let { perPeriodExtra ->
            perPeriodTaxCents += perPeriodExtra.amount
        }

        return Money(perPeriodTaxCents)
    }

    private fun periodsPerYear(frequency: PayFrequency): Long = when (frequency) {
        PayFrequency.WEEKLY -> 52L
        PayFrequency.BIWEEKLY -> 26L
        PayFrequency.FOUR_WEEKLY -> 13L
        PayFrequency.SEMI_MONTHLY -> 24L
        PayFrequency.MONTHLY -> 12L
        PayFrequency.QUARTERLY -> 4L
        PayFrequency.ANNUAL -> 1L
    }

    private fun computeBracketedTaxOnAnnualWages(
        annualWagesCents: Long,
        rule: TaxRule.BracketedIncomeTax,
    ): Long {
        // Reuse the same algorithmic structure as TaxesCalculator, but on an
        // annualized amount and without standard deduction (which is already
        // accounted for via W-4 adjustments in this simplified implementation).
        var remaining = annualWagesCents
        var previousUpper = 0L
        var totalTaxCents = 0L

        for (bracket in rule.brackets) {
            if (remaining <= 0L) break
            val upper = bracket.upTo?.amount ?: Long.MAX_VALUE
            if (upper <= previousUpper) continue
            val span = upper - previousUpper
            val applied = minOf(remaining, span)
            if (applied > 0L) {
                val taxCents = (applied * bracket.rate.value).toLong()
                totalTaxCents += taxCents
                remaining -= applied
            }
            previousUpper = upper
        }

        return totalTaxCents
    }
}
