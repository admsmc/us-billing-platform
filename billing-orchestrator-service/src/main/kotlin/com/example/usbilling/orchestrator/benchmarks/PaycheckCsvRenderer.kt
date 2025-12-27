package com.example.usbilling.orchestrator.benchmarks

import com.example.usbilling.payroll.model.PaycheckResult
import com.example.usbilling.payroll.model.TaxBasis
import java.util.Locale

/**
 * Benchmark/export helper: render paychecks as a wide CSV.
 *
 * One paycheck = one row.
 * Pay elements become columns:
 * - earnings by code
 * - employee taxes by (jurisdiction type, jurisdiction code, ruleId)
 * - employer taxes by (jurisdiction type, jurisdiction code, ruleId)
 * - deductions by code
 * - employer contributions by code
 */
object PaycheckCsvRenderer {

    data class ColumnSet(
        val ytdWageBases: List<String>,
        val earningCodes: List<String>,
        val employeeTaxKeys: List<String>,
        val employerTaxKeys: List<String>,
        val deductionCodes: List<String>,
        val employerContribCodes: List<String>,
    )

    fun renderWideCsv(paychecks: List<PaycheckResult>, includeHeader: Boolean = true): ByteArray {
        val cols = deriveColumns(paychecks)

        val sb = StringBuilder(16_384)

        if (includeHeader) {
            val header = buildList {
                add("employerId")
                add("payPeriodId")
                add("checkDate")
                add("frequency")
                add("paycheckId")
                add("payRunId")
                add("employeeId")
                add("grossCents")
                add("netCents")

                // YTD totals (derived from YtdSnapshot maps)
                add("ytdYear")
                add("ytdEarningsCents")
                add("ytdNetCents")
                add("ytdEmployeeTaxesCents")
                add("ytdEmployerTaxesCents")
                add("ytdDeductionsCents")
                add("ytdEmployerContributionsCents")

                // YTD tax bases
                for (b in cols.ytdWageBases) add("ytdWages.$b.cents")

                // Earnings: amount + units + derived rate
                for (c in cols.earningCodes) add("earning.$c.cents")
                for (c in cols.earningCodes) add("earning.$c.units")
                for (c in cols.earningCodes) add("earning.$c.rateCents")

                for (k in cols.employeeTaxKeys) add("employeeTax.$k.cents")
                for (k in cols.employerTaxKeys) add("employerTax.$k.cents")
                for (c in cols.deductionCodes) add("deduction.$c.cents")
                for (c in cols.employerContribCodes) add("employerContrib.$c.cents")
            }

            sb.append(header.joinToString(",") { csvEscape(it) })
            sb.append('\n')
        }

        for (p in paychecks) {
            data class EarningAgg(
                var amountCents: Long = 0L,
                var units: Double = 0.0,
            )

            val earningsAgg: Map<String, EarningAgg> = run {
                val m = HashMap<String, EarningAgg>()
                for (e in p.earnings) {
                    val key = e.code.value
                    val agg = m.getOrPut(key) { EarningAgg() }
                    agg.amountCents += e.amount.amount
                    agg.units += e.units
                }
                m
            }

            val employeeTaxByKey = p.employeeTaxes
                .groupBy { taxKey(it.ruleId, it.jurisdiction.type.name, it.jurisdiction.code) }
                .mapValues { (_, lines) -> lines.sumOf { it.amount.amount } }

            val employerTaxByKey = p.employerTaxes
                .groupBy { taxKey(it.ruleId, it.jurisdiction.type.name, it.jurisdiction.code) }
                .mapValues { (_, lines) -> lines.sumOf { it.amount.amount } }

            val deductionByCode = p.deductions
                .groupBy { it.code.value }
                .mapValues { (_, lines) -> lines.sumOf { it.amount.amount } }

            val employerContribByCode = p.employerContributions
                .groupBy { it.code.value }
                .mapValues { (_, lines) -> lines.sumOf { it.amount.amount } }

            val y = p.ytdAfter
            val ytdEarningsCents = y.earningsByCode.values.sumOf { it.amount }
            val ytdEmployeeTaxesCents = y.employeeTaxesByRuleId.values.sumOf { it.amount }
            val ytdEmployerTaxesCents = y.employerTaxesByRuleId.values.sumOf { it.amount }
            val ytdDeductionsCents = y.deductionsByCode.values.sumOf { it.amount }
            val ytdEmployerContribCents = y.employerContributionsByCode.values.sumOf { it.amount }
            val ytdNetCents = ytdEarningsCents - ytdEmployeeTaxesCents - ytdDeductionsCents

            val row = buildList {
                add(p.employerId.value)
                add(p.period.id)
                add(p.period.checkDate.toString())
                add(p.period.frequency.name)
                add(p.paycheckId.value)
                add(p.payRunId?.value ?: "")
                add(p.employeeId.value)
                add(p.gross.amount.toString())
                add(p.net.amount.toString())

                add(y.year.toString())
                add(ytdEarningsCents.toString())
                add(ytdNetCents.toString())
                add(ytdEmployeeTaxesCents.toString())
                add(ytdEmployerTaxesCents.toString())
                add(ytdDeductionsCents.toString())
                add(ytdEmployerContribCents.toString())

                // YTD tax bases
                val wagesByBasisKeyed = y.wagesByBasis
                    .mapKeys { (basis, _) -> taxBasisKey(basis) }
                for (b in cols.ytdWageBases) {
                    val cents = wagesByBasisKeyed[b]?.amount ?: 0L
                    add(cents.toString())
                }

                // Earnings
                for (c in cols.earningCodes) {
                    val agg = earningsAgg[c]
                    add((agg?.amountCents ?: 0L).toString())
                }
                for (c in cols.earningCodes) {
                    val units = earningsAgg[c]?.units ?: 0.0
                    add(formatDouble(units))
                }
                for (c in cols.earningCodes) {
                    val agg = earningsAgg[c]
                    val rate = if (agg == null || agg.units <= 0.0) {
                        ""
                    } else {
                        val centsPerUnit = kotlin.math.floor(agg.amountCents.toDouble() / agg.units).toLong()
                        centsPerUnit.toString()
                    }
                    add(rate)
                }

                for (k in cols.employeeTaxKeys) add((employeeTaxByKey[k] ?: 0L).toString())
                for (k in cols.employerTaxKeys) add((employerTaxByKey[k] ?: 0L).toString())
                for (c in cols.deductionCodes) add((deductionByCode[c] ?: 0L).toString())
                for (c in cols.employerContribCodes) add((employerContribByCode[c] ?: 0L).toString())
            }

            sb.append(row.joinToString(",") { csvEscape(it) })
            sb.append('\n')
        }

        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun deriveColumns(paychecks: List<PaycheckResult>): ColumnSet {
        val ytdWageBases = paychecks
            .asSequence()
            .map { it.ytdAfter }
            .flatMap { it.wagesByBasis.keys.asSequence() }
            .map { taxBasisKey(it) }
            .toSortedSet()
            .toList()

        val earningCodes = paychecks
            .asSequence()
            .flatMap { it.earnings.asSequence() }
            .map { it.code.value }
            .toSortedSet()
            .toList()

        val employeeTaxKeys = paychecks
            .asSequence()
            .flatMap { it.employeeTaxes.asSequence() }
            .map { taxKey(it.ruleId, it.jurisdiction.type.name, it.jurisdiction.code) }
            .toSortedSet()
            .toList()

        val employerTaxKeys = paychecks
            .asSequence()
            .flatMap { it.employerTaxes.asSequence() }
            .map { taxKey(it.ruleId, it.jurisdiction.type.name, it.jurisdiction.code) }
            .toSortedSet()
            .toList()

        val deductionCodes = paychecks
            .asSequence()
            .flatMap { it.deductions.asSequence() }
            .map { it.code.value }
            .toSortedSet()
            .toList()

        val employerContribCodes = paychecks
            .asSequence()
            .flatMap { it.employerContributions.asSequence() }
            .map { it.code.value }
            .toSortedSet()
            .toList()

        return ColumnSet(
            ytdWageBases = ytdWageBases,
            earningCodes = earningCodes,
            employeeTaxKeys = employeeTaxKeys,
            employerTaxKeys = employerTaxKeys,
            deductionCodes = deductionCodes,
            employerContribCodes = employerContribCodes,
        )
    }

    private fun taxKey(ruleId: String, jurisdictionType: String, jurisdictionCode: String): String = "$jurisdictionType.$jurisdictionCode.$ruleId"

    private fun taxBasisKey(basis: TaxBasis): String = when (basis) {
        TaxBasis.Gross -> "Gross"
        TaxBasis.FederalTaxable -> "FederalTaxable"
        TaxBasis.StateTaxable -> "StateTaxable"
        TaxBasis.SocialSecurityWages -> "SocialSecurityWages"
        TaxBasis.MedicareWages -> "MedicareWages"
        TaxBasis.SupplementalWages -> "SupplementalWages"
        TaxBasis.FutaWages -> "FutaWages"
    }

    private fun formatDouble(v: Double): String = String.format(Locale.US, "%.4f", v)

    private fun csvEscape(v: String): String {
        val needsQuote = v.indexOfAny(charArrayOf(',', '"', '\n', '\r')) >= 0
        if (!needsQuote) return v
        return "\"" + v.replace("\"", "\"\"") + "\""
    }
}
