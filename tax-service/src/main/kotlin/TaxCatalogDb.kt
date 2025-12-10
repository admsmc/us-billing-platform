package com.example.uspayroll.tax.impl

import com.example.uspayroll.payroll.model.*
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
import com.example.uspayroll.tax.api.TaxCatalog
import com.example.uspayroll.tax.api.TaxQuery
import com.example.uspayroll.tax.config.TaxBracketConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.LocalDate

/**
 * Raw record shape for a tax rule as stored in the persistence layer.
 *
 * This is intentionally close to the database schema and is adapted into the
 * payroll-domain [TaxRule] model via [toDomainTaxRule].
 */
data class TaxRuleRecord(
    val id: String,
    val jurisdictionType: TaxJurisdictionType,
    val jurisdictionCode: String,
    val basis: TaxBasis,
    val ruleType: RuleType,
    val rate: Double?,
    val annualWageCapCents: Long?,
    val bracketsJson: String?,
    val standardDeductionCents: Long?,
    val additionalWithholdingCents: Long?,
    // Real-world selection fields. These are used by the persistence layer
    // to choose which rules apply for a given employer/date/employee context.
    val employerId: EmployerId? = null,
    val effectiveFrom: LocalDate? = null,
    val effectiveTo: LocalDate? = null,
    val filingStatus: String? = null,
    val residentStateFilter: String? = null,
    val workStateFilter: String? = null,
    val localityFilter: String? = null,
) {
    enum class RuleType { FLAT, BRACKETED, WAGE_BRACKET }
}

/**
 * Repository abstraction for retrieving tax rules from the underlying store.
 *
 * A concrete implementation will typically query a relational database using
 * [TaxQuery] fields (employer, date, jurisdiction, etc.) and effective-dated
 * tax rule tables.
 */
interface TaxRuleRepository {
    fun findRulesFor(query: TaxQuery): List<TaxRuleRecord>
}

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
                rate = Percent(requireNotNull(rate) {
                    "Flat tax rule '$id' is missing rate"
                }),
                annualWageCap = normalizedCapCents?.let { Money(it) },
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
                        error("Unknown filing status '$raw' on tax rule '$id'")
                    }
                },
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
                        error("Unknown filing status '$raw' on tax rule '$id'")
                    }
                },
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
