package com.example.usbilling.payroll.engine

import com.example.usbilling.payroll.model.DeductionLine
import com.example.usbilling.payroll.model.EarningLine
import com.example.usbilling.payroll.model.EmployerContributionLine
import com.example.usbilling.payroll.model.TaxBasis
import com.example.usbilling.payroll.model.TaxLine
import com.example.usbilling.payroll.model.YtdSnapshot
import com.example.usbilling.shared.Money

/**
 * Updates YtdSnapshot based on the current paycheck's earnings, taxes, and deductions.
 */
object YtdAccumulator {

    fun update(
        prior: YtdSnapshot,
        earnings: List<EarningLine>,
        employeeTaxes: List<TaxLine>,
        employerTaxes: List<TaxLine>,
        deductions: List<DeductionLine>,
        bases: Map<TaxBasis, Money>,
        employerContributions: List<EmployerContributionLine>,
    ): YtdSnapshot {
        val earningsByCode = prior.earningsByCode.toMutableMap()
        earnings.forEach { line ->
            val existing = earningsByCode[line.code]
            val sumCents = (existing?.amount ?: 0L) + line.amount.amount
            earningsByCode[line.code] = Money(sumCents, line.amount.currency)
        }

        val employeeTaxesByRuleId = prior.employeeTaxesByRuleId.toMutableMap()
        employeeTaxes.forEach { line ->
            val existing = employeeTaxesByRuleId[line.ruleId]
            val sumCents = (existing?.amount ?: 0L) + line.amount.amount
            employeeTaxesByRuleId[line.ruleId] = Money(sumCents, line.amount.currency)
        }

        val employerTaxesByRuleId = prior.employerTaxesByRuleId.toMutableMap()
        employerTaxes.forEach { line ->
            val existing = employerTaxesByRuleId[line.ruleId]
            val sumCents = (existing?.amount ?: 0L) + line.amount.amount
            employerTaxesByRuleId[line.ruleId] = Money(sumCents, line.amount.currency)
        }

        val deductionsByCode = prior.deductionsByCode.toMutableMap()
        deductions.forEach { line ->
            val existing = deductionsByCode[line.code]
            val sumCents = (existing?.amount ?: 0L) + line.amount.amount
            deductionsByCode[line.code] = Money(sumCents, line.amount.currency)
        }

        val wagesByBasis = prior.wagesByBasis.toMutableMap()
        bases.forEach { (basis, amount) ->
            val existing = wagesByBasis[basis]
            val sumCents = (existing?.amount ?: 0L) + amount.amount
            wagesByBasis[basis] = Money(sumCents, amount.currency)
        }

        val employerContribsByCode = prior.employerContributionsByCode.toMutableMap()
        employerContributions.forEach { line ->
            val existing = employerContribsByCode[line.code]
            val sumCents = (existing?.amount ?: 0L) + line.amount.amount
            employerContribsByCode[line.code] = Money(sumCents, line.amount.currency)
        }

        return prior.copy(
            earningsByCode = earningsByCode,
            employeeTaxesByRuleId = employeeTaxesByRuleId,
            employerTaxesByRuleId = employerTaxesByRuleId,
            deductionsByCode = deductionsByCode,
            wagesByBasis = wagesByBasis,
            employerContributionsByCode = employerContribsByCode,
        )
    }
}
