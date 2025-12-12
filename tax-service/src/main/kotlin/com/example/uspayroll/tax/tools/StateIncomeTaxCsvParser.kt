package com.example.uspayroll.tax.tools

import com.example.uspayroll.tax.config.TaxBracketConfig
import com.example.uspayroll.tax.config.TaxRuleConfig
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.Reader
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Parses state income tax CSV files into [TaxRuleConfig] objects suitable for
 * serialization as TaxRuleFile JSON.
 *
 * There are two CSVs per tax year:
 *
 *  1. Rules: state-income-tax-YYYY-rules.csv
 *     Columns:
 *       - state_code (e.g. CA)
 *       - tax_year (e.g. 2025)
 *       - filing_status (e.g. SINGLE, MARRIED, HEAD_OF_HOUSEHOLD or blank)
 *       - rule_type (FLAT or BRACKETED)
 *       - basis (usually StateTaxable; defaults to StateTaxable if blank)
 *       - flat_rate (e.g. 0.050 for 5%; optional, for FLAT rules)
 *       - standard_deduction (dollars; optional)
 *       - additional_withholding (dollars; optional)
 *       - effective_from (YYYY-MM-DD; defaults to 2025-01-01 if blank)
 *       - effective_to (YYYY-MM-DD; defaults to 9999-12-31 if blank)
 *       - resident_state_filter (defaults to state_code if blank)
 *       - work_state_filter (optional)
 *
 *  2. Brackets: state-income-tax-YYYY-brackets.csv
 *     Columns:
 *       - state_code
 *       - tax_year
 *       - filing_status
 *       - bracket_index (integer; sort order)
 *       - up_to (dollars; blank for no cap)
 *       - rate (e.g. 0.050 for 5%)
 */
object StateIncomeTaxCsvParser {

    private val csvFormat: CSVFormat = CSVFormat.DEFAULT
        .withFirstRecordAsHeader()
        .withTrim()
        .withIgnoreEmptyLines()
        .withIgnoreSurroundingSpaces()

    data class RuleRow(
        val stateCode: String,
        val taxYear: Int,
        val filingStatus: String?,
        val ruleType: String,
        val basis: String,
        val flatRate: Double?,
        val standardDeductionCents: Long?,
        val additionalWithholdingCents: Long?,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate,
        val residentStateFilter: String?,
        val workStateFilter: String?,
    )

    data class BracketRow(
        val stateCode: String,
        val taxYear: Int,
        val filingStatus: String?,
        val bracketIndex: Int,
        val upToCents: Long?,
        val rate: Double,
    )

    fun parse(rulesReader: Reader, bracketsReader: Reader): List<TaxRuleConfig> {
        val rules = csvFormat.parse(rulesReader).mapNotNull { toRuleRow(it) }
        val brackets = csvFormat.parse(bracketsReader).mapNotNull { toBracketRow(it) }

        val bracketsByKey: Map<Triple<String, Int, String?>, List<BracketRow>> =
            brackets.groupBy { Triple(it.stateCode, it.taxYear, it.filingStatus) }

        return rules.map { rule ->
            val key = Triple(rule.stateCode, rule.taxYear, rule.filingStatus)
            val matchingBrackets = bracketsByKey[key]
                ?.sortedBy { it.bracketIndex }
                ?.map { TaxBracketConfig(upToCents = it.upToCents, rate = it.rate) }

            toTaxRuleConfig(rule, matchingBrackets)
        }
    }

    private fun toRuleRow(rec: CSVRecord): RuleRow? {
        val stateCode = rec.get("state_code").trim().uppercase()
        if (stateCode.isEmpty()) return null

        val taxYear = rec.get("tax_year").trim().toInt()

        val filingStatusRaw = rec.getOrNull("filing_status")?.trim()
        val filingStatus = filingStatusRaw?.takeIf { it.isNotEmpty() }

        val ruleTypeRaw = rec.getOrNull("rule_type")?.trim().orEmpty()
        if (ruleTypeRaw.isEmpty()) return null // skip rows not yet configured
        val ruleType = ruleTypeRaw.uppercase()

        val basis = rec.getOrNull("basis")?.trim().takeUnless { it.isNullOrEmpty() } ?: "StateTaxable"

        val flatRate = parseDouble(rec.getOrNull("flat_rate"))
        val standardDeductionCents = parseMoneyToCents(rec.getOrNull("standard_deduction"))
        val additionalWithholdingCents = parseMoneyToCents(rec.getOrNull("additional_withholding"))

        val effectiveFrom = rec.getOrNull("effective_from")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(LocalDate::parse)
            ?: LocalDate.of(taxYear, 1, 1)

        val effectiveTo = rec.getOrNull("effective_to")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(LocalDate::parse)
            ?: LocalDate.of(9999, 12, 31)

        val residentStateFilter = rec.getOrNull("resident_state_filter")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: stateCode

        val workStateFilter = rec.getOrNull("work_state_filter")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return RuleRow(
            stateCode = stateCode,
            taxYear = taxYear,
            filingStatus = filingStatus,
            ruleType = ruleType,
            basis = basis,
            flatRate = flatRate,
            standardDeductionCents = standardDeductionCents,
            additionalWithholdingCents = additionalWithholdingCents,
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo,
            residentStateFilter = residentStateFilter,
            workStateFilter = workStateFilter,
        )
    }

    private fun toBracketRow(rec: CSVRecord): BracketRow? {
        val stateCode = rec.get("state_code").trim().uppercase()
        if (stateCode.isEmpty()) return null
        val taxYear = rec.get("tax_year").trim().toInt()

        val filingStatusRaw = rec.getOrNull("filing_status")?.trim()
        val filingStatus = filingStatusRaw?.takeIf { it.isNotEmpty() }

        val bracketIndex = rec.get("bracket_index").trim().toInt()
        val upToCents = parseMoneyToCents(rec.getOrNull("up_to"))
        val rate = parseDouble(rec.getOrNull("rate"))
            ?: throw IllegalArgumentException("Missing rate for state income tax bracket: $stateCode/$taxYear index=$bracketIndex")

        return BracketRow(
            stateCode = stateCode,
            taxYear = taxYear,
            filingStatus = filingStatus,
            bracketIndex = bracketIndex,
            upToCents = upToCents,
            rate = rate,
        )
    }

    private fun toTaxRuleConfig(rule: RuleRow, brackets: List<TaxBracketConfig>?): TaxRuleConfig {
        val idSuffix = buildString {
            append(rule.stateCode)
            append("_SIT_")
            append(rule.taxYear)
            rule.filingStatus?.let { append("_").append(it) }
        }
        val id = "US_$idSuffix"

        return TaxRuleConfig(
            id = id,
            jurisdictionType = "STATE",
            jurisdictionCode = rule.stateCode,
            basis = rule.basis,
            ruleType = rule.ruleType,
            rate = rule.flatRate,
            annualWageCapCents = null,
            brackets = brackets,
            standardDeductionCents = rule.standardDeductionCents,
            additionalWithholdingCents = rule.additionalWithholdingCents,
            employerId = null,
            effectiveFrom = rule.effectiveFrom,
            effectiveTo = rule.effectiveTo,
            filingStatus = rule.filingStatus,
            residentStateFilter = rule.residentStateFilter,
            workStateFilter = rule.workStateFilter,
            localityFilter = null,
        )
    }

    private fun parseMoneyToCents(raw: String?): Long? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val bd = try {
            BigDecimal(trimmed)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid money value '$trimmed' in state income tax CSV", e)
        }
        return bd
            .setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2)
            .longValueExact()
    }

    private fun parseDouble(raw: String?): Double? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            trimmed.toDouble()
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid numeric value '$trimmed' in state income tax CSV", e)
        }
    }

    private fun CSVRecord.getOrNull(name: String): String? = if (this.isMapped(name)) this.get(name) else null
}
