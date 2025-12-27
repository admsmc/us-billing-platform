package com.example.usbilling.tax.tools

import com.example.usbilling.tax.config.TaxBracketConfig
import com.example.usbilling.tax.config.TaxRuleConfig
import java.io.Reader

/**
 * Minimal parser for IRS-style wage-bracket CSV files.
 *
 * This is intentionally generic: it does not hard-code year or filing status,
 * only the CSV column semantics. A separate CLI (WageBracketCsvImporter)
 * drives which CSVs to parse and where to write the resulting JSON.
 *
 * CSV schema (header row required):
 *   frequency,filingStatus,variant,minCents,maxCents,taxCents
 *
 * Where:
 * - frequency: WEEKLY, BIWEEKLY, etc. (currently informational; the importer
 *   will choose output filenames based on it).
 * - filingStatus: SINGLE, MARRIED, HEAD_OF_HOUSEHOLD.
 * - variant: STANDARD or STEP2_CHECKBOX.
 * - minCents/maxCents: inclusive/exclusive wage band endpoints in cents; the
 *   importer converts these into upToCents for the TaxBracketConfig entries.
 * - taxCents: tax amount for this band in cents.
 */
object WageBracketCsvParser {

    data class Row(
        val frequency: String,
        val filingStatus: String,
        val variant: String,
        val minCents: Long,
        val maxCents: Long?,
        val taxCents: Long,
    )

    fun parse(reader: Reader): List<Row> {
        val rows = mutableListOf<Row>()
        reader.use { r ->
            val lines = r.readLines()
            if (lines.isEmpty()) return emptyList()

            var sawHeader = false
            for (line in lines) {
                if (line.isBlank()) continue
                if (line.trim().startsWith("#")) continue

                if (!sawHeader) {
                    // Treat the first non-comment, non-blank line as the header row.
                    sawHeader = true
                    continue
                }

                val cols = line.split(',')
                require(cols.size >= 6) {
                    "Expected at least 6 columns, got ${cols.size}: '$line'"
                }
                val frequency = cols[0].trim()
                val filingStatus = cols[1].trim()
                val variant = cols[2].trim()
                val minCents = cols[3].trim().toLong()
                val maxRaw = cols[4].trim()
                val maxCents = if (maxRaw.isEmpty()) null else maxRaw.toLong()
                val taxCents = cols[5].trim().toLong()

                rows += Row(
                    frequency = frequency,
                    filingStatus = filingStatus,
                    variant = variant,
                    minCents = minCents,
                    maxCents = maxCents,
                    taxCents = taxCents,
                )
            }
        }
        return rows
    }

    /**
     * Group parsed rows into TaxRuleConfig objects, one per filingStatus +
     * variant combination.
     *
     * @param jurisdictionType e.g., "FEDERAL".
     * @param jurisdictionCode e.g., "US".
     * @param basis e.g., "FederalTaxable".
     * @param baseIdPrefix e.g., "US_FED_FIT_2025_PUB15T_WB"; we will derive
     *                      full IDs by appending filing status and variant.
     */
    fun toTaxRuleConfigs(
        rows: List<Row>,
        jurisdictionType: String,
        jurisdictionCode: String,
        basis: String,
        baseIdPrefix: String,
        effectiveFrom: java.time.LocalDate,
        effectiveTo: java.time.LocalDate,
    ): List<TaxRuleConfig> {
        if (rows.isEmpty()) return emptyList()

        return rows
            .groupBy { it.filingStatus to it.variant }
            .map { (key, groupRows) ->
                val (filingStatus, variant) = key

                val sorted = groupRows.sortedBy { it.maxCents ?: Long.MAX_VALUE }
                val brackets = sorted.map { r ->
                    TaxBracketConfig(
                        upToCents = r.maxCents,
                        rate = 0.0,
                        taxCents = r.taxCents,
                    )
                }

                val idSuffix = when (variant.uppercase()) {
                    "STANDARD" -> "${filingStatus.uppercase()}_BI"
                    "STEP2_CHECKBOX" -> "${filingStatus.uppercase()}_BI_STEP2"
                    else -> "${filingStatus.uppercase()}_BI_${variant.uppercase()}"
                }

                TaxRuleConfig(
                    id = "${baseIdPrefix}_$idSuffix",
                    jurisdictionType = jurisdictionType,
                    jurisdictionCode = jurisdictionCode,
                    basis = basis,
                    ruleType = "WAGE_BRACKET",
                    rate = null,
                    annualWageCapCents = null,
                    brackets = brackets,
                    standardDeductionCents = null,
                    additionalWithholdingCents = null,
                    employerId = null,
                    fitVariant = variant,
                    effectiveFrom = effectiveFrom,
                    effectiveTo = effectiveTo,
                    filingStatus = filingStatus,
                    residentStateFilter = null,
                    workStateFilter = null,
                    localityFilter = null,
                )
            }
    }
}
