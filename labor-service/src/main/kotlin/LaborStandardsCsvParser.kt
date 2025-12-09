package com.example.uspayroll.labor.tools

import com.example.uspayroll.labor.api.LaborStandardSourceKind
import com.example.uspayroll.labor.api.LaborStandardSourceRef
import com.example.uspayroll.labor.api.StateLaborStandard
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVRecord
import java.io.Reader
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Parser for the intermediate DOL/state labor standards CSV.
 *
 * Expected header (columns):
 *  - state_code (e.g. CA, TX)
 *  - effective_from (YYYY-MM-DD)
 *  - effective_to (YYYY-MM-DD or blank)
 *  - regular_min_wage (dollars, e.g. 16.00)
 *  - tipped_min_cash_wage (dollars)
 *  - max_tip_credit (dollars)
 *  - weekly_ot_hours (e.g. 40.0; defaults to 40.0 if blank)
 *  - daily_ot_hours (e.g. 8.0; optional)
 *  - daily_dt_hours (e.g. 12.0; optional)
 *  - min_wage_citation
 *  - min_wage_url
 *  - tipped_citation
 *  - tipped_url
 *  - state_statute_citation
 *  - state_statute_url
 */
object LaborStandardsCsvParser {

    private val csvFormat: CSVFormat = CSVFormat.DEFAULT
        .withFirstRecordAsHeader()
        .withTrim()
        .withIgnoreEmptyLines()
        .withIgnoreSurroundingSpaces()

    fun parse(reader: Reader): List<StateLaborStandard> {
        val parser = csvFormat.parse(reader)
        return parser.map { toStateStandard(it) }
    }

    private fun toStateStandard(rec: CSVRecord): StateLaborStandard {
        val stateCode = rec.getRequired("state_code").trim().uppercase()
        val effectiveFrom = LocalDate.parse(rec.getRequired("effective_from").trim())
        val effectiveTo = rec.getOrNull("effective_to")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(LocalDate::parse)

        val regularMinWageCents = parseMoneyToCents(rec.getOrNull("regular_min_wage"))
        val tippedMinCashWageCents = parseMoneyToCents(rec.getOrNull("tipped_min_cash_wage"))
        val maxTipCreditCents = parseMoneyToCents(rec.getOrNull("max_tip_credit"))

        val weeklyOtHours = parseDouble(rec.getOrNull("weekly_ot_hours")) ?: 40.0
        val dailyOtHours = parseDouble(rec.getOrNull("daily_ot_hours"))
        val dailyDtHours = parseDouble(rec.getOrNull("daily_dt_hours"))

        val minWageCitation = rec.getOrNull("min_wage_citation")?.trim().orEmpty()
        val minWageUrl = rec.getOrNull("min_wage_url")?.trim().orEmpty()
        val tippedCitation = rec.getOrNull("tipped_citation")?.trim().orEmpty()
        val tippedUrl = rec.getOrNull("tipped_url")?.trim().orEmpty()
        val statuteCitation = rec.getOrNull("state_statute_citation")?.trim().orEmpty()
        val statuteUrl = rec.getOrNull("state_statute_url")?.trim().orEmpty()

        val sources = mutableListOf<LaborStandardSourceRef>()

        if (minWageCitation.isNotEmpty() || minWageUrl.isNotEmpty()) {
            sources += LaborStandardSourceRef(
                kind = LaborStandardSourceKind.FEDERAL_DOL_MIN_WAGE_TABLE,
                citation = minWageCitation.ifEmpty { null },
                url = minWageUrl.ifEmpty { null },
            )
        }

        if (tippedCitation.isNotEmpty() || tippedUrl.isNotEmpty()) {
            sources += LaborStandardSourceRef(
                kind = LaborStandardSourceKind.FEDERAL_DOL_TIPPED_WAGE_TABLE,
                citation = tippedCitation.ifEmpty { null },
                url = tippedUrl.ifEmpty { null },
            )
        }

        if (statuteCitation.isNotEmpty() || statuteUrl.isNotEmpty()) {
            sources += LaborStandardSourceRef(
                kind = LaborStandardSourceKind.STATE_STATUTE,
                citation = statuteCitation.ifEmpty { null },
                url = statuteUrl.ifEmpty { null },
            )
        }

        return StateLaborStandard(
            stateCode = stateCode,
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo,
            regularMinimumWageCents = regularMinWageCents,
            tippedMinimumCashWageCents = tippedMinCashWageCents,
            maxTipCreditCents = maxTipCreditCents,
            weeklyOvertimeThresholdHours = weeklyOtHours,
            dailyOvertimeThresholdHours = dailyOtHours,
            dailyDoubleTimeThresholdHours = dailyDtHours,
            sources = sources,
        )
    }

    private fun parseMoneyToCents(raw: String?): Long? {
        if (raw == null) return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val bd = try {
            BigDecimal(trimmed)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid money value '$trimmed' in labor standards CSV", e)
        }
        // dollars -> cents, half-up
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
            throw IllegalArgumentException("Invalid numeric value '$trimmed' in labor standards CSV", e)
        }
    }

    private fun CSVRecord.getRequired(name: String): String =
        this.get(name) ?: throw IllegalArgumentException("Missing required column '$name' in labor standards CSV")

    private fun CSVRecord.getOrNull(name: String): String? =
        if (this.isMapped(name)) this.get(name) else null
}
