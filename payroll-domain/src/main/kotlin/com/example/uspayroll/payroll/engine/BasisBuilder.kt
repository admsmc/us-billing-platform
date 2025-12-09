package com.example.uspayroll.payroll.engine

import com.example.uspayroll.shared.Money
import com.example.uspayroll.payroll.model.DeductionLine
import com.example.uspayroll.payroll.model.DeductionCode
import com.example.uspayroll.payroll.model.EarningLine
import com.example.uspayroll.payroll.model.TaxBasis
import com.example.uspayroll.payroll.model.YtdSnapshot
import com.example.uspayroll.payroll.model.config.DeductionEffect
import com.example.uspayroll.payroll.model.config.DeductionKind
import com.example.uspayroll.payroll.model.config.DeductionPlan
import com.example.uspayroll.payroll.model.config.defaultEmployeeEffects

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

    fun compute(context: BasisContext): BasisComputation {
        val grossAmount = context.earnings.fold(0L) { acc, line -> acc + line.amount.amount }
        val gross = Money(grossAmount)

        val supplementalAmount = context.earnings
            .filter { it.category == com.example.uspayroll.payroll.model.EarningCategory.SUPPLEMENTAL || it.category == com.example.uspayroll.payroll.model.EarningCategory.BONUS }
            .fold(0L) { acc, line -> acc + line.amount.amount }
        val holidayAmount = context.earnings
            .filter { it.category == com.example.uspayroll.payroll.model.EarningCategory.HOLIDAY }
            .fold(0L) { acc, line -> acc + line.amount.amount }
        val imputedAmount = context.earnings
            .filter { it.category == com.example.uspayroll.payroll.model.EarningCategory.IMPUTED }
            .fold(0L) { acc, line -> acc + line.amount.amount }

        fun effectsFor(line: DeductionLine, isPreTax: Boolean): Set<DeductionEffect> {
            val plan = context.plansByCode[line.code]
            val planEffects = when {
                plan == null -> emptySet()
                plan.employeeEffects.isNotEmpty() -> plan.employeeEffects
                else -> plan.kind.defaultEmployeeEffects()
            }
            // Fallback: if we have no plan but the deduction is pre-tax in the
            // engine pipeline, at least reduce FederalTaxable to preserve
            // existing behavior in tests that do not configure plans.
            return if (plan == null && isPreTax) {
                setOf(DeductionEffect.REDUCES_FEDERAL_TAXABLE)
            } else {
                planEffects
            }
        }

        fun sumFor(effect: DeductionEffect): Long {
            val fromPreTax = context.preTaxDeductions.sumOf { line ->
                val effects = effectsFor(line, isPreTax = true)
                if (effect in effects) line.amount.amount else 0L
            }
            val fromPostTax = context.postTaxDeductions.sumOf { line ->
                val effects = effectsFor(line, isPreTax = false)
                if (effect in effects) line.amount.amount else 0L
            }
            return fromPreTax + fromPostTax
        }

        val federalReductions = sumFor(DeductionEffect.REDUCES_FEDERAL_TAXABLE)
        val stateReductions = sumFor(DeductionEffect.REDUCES_STATE_TAXABLE)
        val ssReductions = sumFor(DeductionEffect.REDUCES_SOCIAL_SECURITY_WAGES)
        val medicareReductions = sumFor(DeductionEffect.REDUCES_MEDICARE_WAGES)

        val federalTaxable = Money(gross.amount - federalReductions, gross.currency)
        val stateTaxable = Money(gross.amount - stateReductions, gross.currency)
        val ssWages = Money(gross.amount - ssReductions, gross.currency)
        val medicareWages = Money(gross.amount - medicareReductions, gross.currency)
        val supplementalWages = Money(supplementalAmount, gross.currency)
        // For now, FUTA wages follow gross wages; more precise FUTA-specific
        // reductions can be added later via additional deduction effects.
        val futaWages = gross

        val bases = buildMap {
            put(TaxBasis.Gross, gross)
            put(TaxBasis.FederalTaxable, federalTaxable)
            put(TaxBasis.StateTaxable, stateTaxable)
            put(TaxBasis.SocialSecurityWages, ssWages)
            put(TaxBasis.MedicareWages, medicareWages)
            put(TaxBasis.SupplementalWages, supplementalWages)
            put(TaxBasis.FutaWages, futaWages)
        }

        val components = buildMap<TaxBasis, Map<String, Money>> {
            put(TaxBasis.Gross, buildMap {
                put("gross", gross)
                if (supplementalAmount != 0L) {
                    put("supplemental", Money(supplementalAmount, gross.currency))
                }
                if (holidayAmount != 0L) {
                    put("holiday", Money(holidayAmount, gross.currency))
                }
                if (imputedAmount != 0L) {
                    put("imputed", Money(imputedAmount, gross.currency))
                }
            })
            put(TaxBasis.FederalTaxable, buildMap {
                put("gross", gross)
                if (federalReductions != 0L) {
                    put("lessFederalTaxableDeductions", Money(federalReductions, gross.currency))
                }
            })
            put(TaxBasis.StateTaxable, buildMap {
                put("gross", gross)
                if (stateReductions != 0L) {
                    put("lessStateTaxableDeductions", Money(stateReductions, gross.currency))
                }
            })
            put(TaxBasis.SocialSecurityWages, buildMap {
                put("gross", gross)
                if (ssReductions != 0L) {
                    put("lessFicaDeductions", Money(ssReductions, gross.currency))
                }
            })
            put(TaxBasis.MedicareWages, buildMap {
                put("gross", gross)
                if (medicareReductions != 0L) {
                    put("lessMedicareDeductions", Money(medicareReductions, gross.currency))
                }
            })
            put(TaxBasis.SupplementalWages, buildMap {
                put("supplemental", Money(supplementalAmount, gross.currency))
            })
            put(TaxBasis.FutaWages, buildMap {
                put("gross", gross)
            })
        }

        return BasisComputation(bases = bases, components = components)
    }
}
