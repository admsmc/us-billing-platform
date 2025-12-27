package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.DeductionCode
import com.example.usbilling.payroll.model.DeductionLine
import com.example.usbilling.payroll.model.EarningLine
import com.example.usbilling.payroll.model.TaxBasis
import com.example.usbilling.payroll.model.YtdSnapshot
import com.example.usbilling.payroll.model.config.DeductionEffect
import com.example.usbilling.payroll.model.config.DeductionPlan
import com.example.usbilling.payroll.model.config.defaultEmployeeEffects
import com.example.usbilling.shared.Money

/**
 * Input to tax basis computation.
 *
 * Phase 1 keeps this simple: bases are derived from earnings and the split
 * between pre-tax and post-tax deductions. YTD is included for future phases
 * (e.g., caps) but is not yet used.
 */
data class BasisContext(
    val earnings: List<EarningLine>,
    val preTaxDeductions: List<DeductionLine>,
    val postTaxDeductions: List<DeductionLine>,
    val plansByCode: Map<DeductionCode, DeductionPlan>,
    val ytd: YtdSnapshot,
)

/**
 * Computes the per-basis wage map used by the tax engine.
 *
 * Phase 2 semantics:
 * - GROSS = sum of all earnings.
 * - Other bases start at GROSS and are reduced by deductions according to
 *   their configured or default [DeductionEffect] values.
 */
data class BasisComputation(
    val bases: Map<TaxBasis, Money>,
    val components: Map<TaxBasis, Map<String, Money>>,
)

object BasisBuilder {

    fun compute(context: BasisContext, includeComponents: Boolean = true): BasisComputation {
        var grossAmount = 0L
        var supplementalAmount = 0L
        var holidayAmount = 0L
        var imputedAmount = 0L

        for (line in context.earnings) {
            val cents = line.amount.amount
            grossAmount += cents

            when (line.category) {
                com.example.usbilling.payroll.model.EarningCategory.SUPPLEMENTAL,
                com.example.usbilling.payroll.model.EarningCategory.BONUS,
                -> supplementalAmount += cents

                com.example.usbilling.payroll.model.EarningCategory.HOLIDAY -> holidayAmount += cents
                com.example.usbilling.payroll.model.EarningCategory.IMPUTED -> imputedAmount += cents
                else -> {
                    // ignore
                }
            }
        }

        val gross = Money(grossAmount)

        var federalReductions = 0L
        var stateReductions = 0L
        var ssReductions = 0L
        var medicareReductions = 0L

        fun applyDeductionEffects(line: DeductionLine, isPreTax: Boolean) {
            val cents = line.amount.amount
            if (cents == 0L) return

            val plan = context.plansByCode[line.code]

            // Fallback: if we have no plan but the deduction is pre-tax in the
            // engine pipeline, at least reduce FederalTaxable to preserve
            // existing behavior in tests that do not configure plans.
            if (plan == null) {
                if (isPreTax) {
                    federalReductions += cents
                }
                return
            }

            val planEffects = if (plan.employeeEffects.isNotEmpty()) {
                plan.employeeEffects
            } else {
                plan.kind.defaultEmployeeEffects()
            }

            // Effects are usually a small set; iterate once and update all totals.
            for (effect in planEffects) {
                when (effect) {
                    DeductionEffect.REDUCES_FEDERAL_TAXABLE -> federalReductions += cents
                    DeductionEffect.REDUCES_STATE_TAXABLE -> stateReductions += cents
                    DeductionEffect.REDUCES_SOCIAL_SECURITY_WAGES -> ssReductions += cents
                    DeductionEffect.REDUCES_MEDICARE_WAGES -> medicareReductions += cents
                    else -> {
                        // ignore: effect does not impact the bases we currently compute
                    }
                }
            }
        }

        for (line in context.preTaxDeductions) {
            applyDeductionEffects(line, isPreTax = true)
        }
        for (line in context.postTaxDeductions) {
            applyDeductionEffects(line, isPreTax = false)
        }

        val federalTaxable = Money(gross.amount - federalReductions, gross.currency)
        val stateTaxable = Money(gross.amount - stateReductions, gross.currency)
        val ssWages = Money(gross.amount - ssReductions, gross.currency)
        val medicareWages = Money(gross.amount - medicareReductions, gross.currency)
        val supplementalWages = Money(supplementalAmount, gross.currency)
        // For now, FUTA wages follow gross wages; more precise FUTA-specific
        // reductions can be added later via additional deduction effects.
        val futaWages = gross

        val bases = LinkedHashMap<TaxBasis, Money>(8)
        bases[TaxBasis.Gross] = gross
        bases[TaxBasis.FederalTaxable] = federalTaxable
        bases[TaxBasis.StateTaxable] = stateTaxable
        bases[TaxBasis.SocialSecurityWages] = ssWages
        bases[TaxBasis.MedicareWages] = medicareWages
        bases[TaxBasis.SupplementalWages] = supplementalWages
        bases[TaxBasis.FutaWages] = futaWages

        val supplementalMoney: Money? = if (supplementalAmount != 0L) Money(supplementalAmount, gross.currency) else null
        val holidayMoney: Money? = if (holidayAmount != 0L) Money(holidayAmount, gross.currency) else null
        val imputedMoney: Money? = if (imputedAmount != 0L) Money(imputedAmount, gross.currency) else null

        val lessFederalTaxableMoney: Money? = if (federalReductions != 0L) Money(federalReductions, gross.currency) else null
        val lessStateTaxableMoney: Money? = if (stateReductions != 0L) Money(stateReductions, gross.currency) else null
        val lessFicaMoney: Money? = if (ssReductions != 0L) Money(ssReductions, gross.currency) else null
        val lessMedicareMoney: Money? = if (medicareReductions != 0L) Money(medicareReductions, gross.currency) else null

        if (!includeComponents) {
            return BasisComputation(bases = bases, components = emptyMap())
        }

        val components = LinkedHashMap<TaxBasis, Map<String, Money>>(8)

        run {
            val grossComponents = LinkedHashMap<String, Money>(4)
            grossComponents["gross"] = gross
            if (supplementalMoney != null) grossComponents["supplemental"] = supplementalMoney
            if (holidayMoney != null) grossComponents["holiday"] = holidayMoney
            if (imputedMoney != null) grossComponents["imputed"] = imputedMoney
            components[TaxBasis.Gross] = grossComponents
        }

        run {
            val federalComponents = LinkedHashMap<String, Money>(2)
            federalComponents["gross"] = gross
            if (lessFederalTaxableMoney != null) {
                federalComponents["lessFederalTaxableDeductions"] = lessFederalTaxableMoney
            }
            components[TaxBasis.FederalTaxable] = federalComponents
        }

        run {
            val stateComponents = LinkedHashMap<String, Money>(2)
            stateComponents["gross"] = gross
            if (lessStateTaxableMoney != null) {
                stateComponents["lessStateTaxableDeductions"] = lessStateTaxableMoney
            }
            components[TaxBasis.StateTaxable] = stateComponents
        }

        run {
            val ssComponents = LinkedHashMap<String, Money>(2)
            ssComponents["gross"] = gross
            if (lessFicaMoney != null) {
                ssComponents["lessFicaDeductions"] = lessFicaMoney
            }
            components[TaxBasis.SocialSecurityWages] = ssComponents
        }

        run {
            val medicareComponents = LinkedHashMap<String, Money>(2)
            medicareComponents["gross"] = gross
            if (lessMedicareMoney != null) {
                medicareComponents["lessMedicareDeductions"] = lessMedicareMoney
            }
            components[TaxBasis.MedicareWages] = medicareComponents
        }

        run {
            // Supplemental basis is always present; keep this stable.
            val supplementalComponents = LinkedHashMap<String, Money>(1)
            supplementalComponents["supplemental"] = Money(supplementalAmount, gross.currency)
            components[TaxBasis.SupplementalWages] = supplementalComponents
        }

        run {
            val futaComponents = LinkedHashMap<String, Money>(1)
            futaComponents["gross"] = gross
            components[TaxBasis.FutaWages] = futaComponents
        }

        return BasisComputation(bases = bases, components = components)
    }
}
