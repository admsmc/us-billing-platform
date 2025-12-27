package com.example.usbilling.tax.impl

import com.example.usbilling.payroll.model.FilingStatus
import com.example.usbilling.payroll.model.Percent
import com.example.usbilling.payroll.model.TaxBracket
import com.example.usbilling.payroll.model.TaxJurisdiction
import com.example.usbilling.payroll.model.TaxRule
import com.example.usbilling.payroll.model.WageBracketRow
import com.example.usbilling.shared.Money
import com.example.usbilling.tax.api.TaxCatalog
import com.example.usbilling.tax.api.TaxQuery
import com.example.usbilling.tax.config.TaxBracketConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Database-backed implementation of [TaxCatalog].
 *
 * This is a thin adapter that delegates to [TaxRuleRepository] and converts the
 * raw records into the payroll-domain [TaxRule] model. The actual persistence
 * logic is left as a TODO for a future iteration.
 */
class DbTaxCatalog(
    private val repository: TaxRuleRepository,
) : TaxCatalog {

    private val objectMapper = jacksonObjectMapper()

    override fun loadRules(query: TaxQuery): List<TaxRule> {
        val records = repository.findRulesFor(query)
        return records.map { it.toDomainTaxRule() }
    }

    private fun TaxRuleRecord.toDomainTaxRule(): TaxRule {
        val jurisdiction = TaxJurisdiction(
            type = jurisdictionType,
            code = jurisdictionCode,
        )

        // Treat non-positive annual wage caps as "no cap" to avoid accidental
        // zero wage bases in the domain model.
        val normalizedCapCents: Long? = annualWageCapCents?.takeIf { it > 0L }

        return when (ruleType) {
            TaxRuleRecord.RuleType.FLAT -> TaxRule.FlatRateTax(
                id = id,
                jurisdiction = jurisdiction,
                basis = basis,
                rate = Percent(
                    requireNotNull(rate) {
                        "Flat tax rule '$id' is missing rate"
                    },
                ),
                annualWageCap = normalizedCapCents?.let { Money(it) },
                localityFilter = localityFilter,
            )
            TaxRuleRecord.RuleType.BRACKETED -> TaxRule.BracketedIncomeTax(
                id = id,
                jurisdiction = jurisdiction,
                basis = basis,
                brackets = parseBrackets(bracketsJson),
                standardDeduction = standardDeductionCents?.let { Money(it) },
                additionalWithholding = additionalWithholdingCents?.let { Money(it) },
                filingStatus = filingStatus?.let { raw ->
                    try {
                        FilingStatus.valueOf(raw)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("Unknown filing status '$raw' on tax rule '$id'", e)
                    }
                },
                localityFilter = localityFilter,
            )
            TaxRuleRecord.RuleType.WAGE_BRACKET -> TaxRule.WageBracketTax(
                id = id,
                jurisdiction = jurisdiction,
                basis = basis,
                brackets = parseWageBrackets(bracketsJson),
                filingStatus = filingStatus?.let { raw ->
                    try {
                        FilingStatus.valueOf(raw)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("Unknown filing status '$raw' on tax rule '$id'", e)
                    }
                },
                localityFilter = localityFilter,
            )
        }
    }

    private fun parseBrackets(json: String?): List<TaxBracket> {
        if (json.isNullOrBlank()) return emptyList()

        val configs: List<TaxBracketConfig> = objectMapper.readValue(json)
        return configs.map { cfg ->
            TaxBracket(
                upTo = cfg.upToCents?.let { Money(it) },
                rate = Percent(cfg.rate),
            )
        }
    }

    private fun parseWageBrackets(json: String?): List<WageBracketRow> {
        if (json.isNullOrBlank()) return emptyList()

        val configs: List<TaxBracketConfig> = objectMapper.readValue(json)
        return configs.mapIndexed { index, cfg ->
            val taxCents = cfg.taxCents
                ?: error("WAGE_BRACKET rule has bracket at index $index without taxCents")
            WageBracketRow(
                upTo = cfg.upToCents?.let { Money(it) },
                tax = Money(taxCents),
            )
        }
    }
}
