package com.example.uspayroll.tax.persistence

import com.example.uspayroll.payroll.model.TaxBasis
import com.example.uspayroll.payroll.model.TaxJurisdictionType
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.tax.api.TaxQuery
import com.example.uspayroll.tax.impl.TaxRuleRecord
import com.example.uspayroll.tax.impl.TaxRuleRepository
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * jOOQ-based implementation of [TaxRuleRepository] backed by a Postgres
 * `tax_rule` table as described in `docs/tax-schema.md`.
 *
 * This class focuses purely on translating a [TaxQuery] into the appropriate
 * SQL WHERE clauses and mapping rows into [TaxRuleRecord]. The surrounding
 * application is responsible for configuring the [DSLContext] with a
 * DataSource and transaction management.
 */
class JooqTaxRuleRepository(
    private val dsl: DSLContext,
) : TaxRuleRepository {

    private val logger = LoggerFactory.getLogger(JooqTaxRuleRepository::class.java)

    private fun <T> Record.getCaseInsensitive(fieldName: String, type: Class<T>): T? {
        val candidates = listOf(fieldName, fieldName.lowercase(), fieldName.uppercase())
        for (candidate in candidates) {
            val value = runCatching { get(candidate, type) }.getOrNull()
            if (value != null) return value
        }
        // If the field exists but the value is NULL, the above will keep trying. Handle that case.
        for (candidate in candidates) {
            val exists = runCatching { fields().any { it.name == candidate } }.getOrDefault(false)
            if (exists) return runCatching { get(candidate, type) }.getOrNull()
        }
        return null
    }

    override fun findRulesFor(query: TaxQuery): List<TaxRuleRecord> {
        val t = DSL.table("tax_rule")

        val conditions = mutableListOf<Condition>()

        // Employer: either a generic rule (employer_id IS NULL) or
        // employer-specific rule matching the query employer.
        conditions += DSL.or(
            DSL.field("employer_id").isNull,
            DSL.field("employer_id").eq(query.employerId.value),
        )

        val asOf = query.asOfDate
        conditions += DSL.field("effective_from", LocalDate::class.java).le(asOf)
        conditions += DSL.field("effective_to", LocalDate::class.java).gt(asOf)

        // Resident state filter: rule applies if it has no resident_state_filter
        // or it matches the employee's resident state.
        query.residentState?.let { state ->
            conditions += DSL.or(
                DSL.field("resident_state_filter").isNull,
                DSL.field("resident_state_filter").eq(state),
            )
        }

        // Work state filter: similar semantics.
        query.workState?.let { state ->
            conditions += DSL.or(
                DSL.field("work_state_filter").isNull,
                DSL.field("work_state_filter").eq(state),
            )
        }

        // Localities:
        // - When none are provided, restrict to rules with no locality_filter
        //   (statutory/generic rules only).
        // - When provided, allow rules that are either generic (NULL locality)
        //   or match one of the requested local jurisdictions.
        if (query.localJurisdictions.isEmpty()) {
            conditions += DSL.field("locality_filter").isNull
        } else {
            conditions += DSL.or(
                DSL.field("locality_filter").isNull,
                DSL.field("locality_filter").`in`(query.localJurisdictions),
            )
        }

        val whereCondition = conditions
            .fold<Condition, Condition?>(null) { acc, c -> acc?.and(c) ?: c }
            ?: DSL.noCondition()

        val records = dsl
            .selectFrom(t)
            .where(whereCondition)
            .fetch()

        logger.debug(
            "Tax rule query for employer={} asOf={} residentState={} workState={} locals={} returned {} row(s)",
            query.employerId.value,
            query.asOfDate,
            query.residentState,
            query.workState,
            query.localJurisdictions,
            records.size,
        )

        return records.map { r ->
            TaxRuleRecord(
                id = requireNotNull(r.getCaseInsensitive("id", String::class.java)),
                jurisdictionType = TaxJurisdictionType.valueOf(requireNotNull(r.getCaseInsensitive("jurisdiction_type", String::class.java))),
                jurisdictionCode = requireNotNull(r.getCaseInsensitive("jurisdiction_code", String::class.java)),
                basis = parseTaxBasis(requireNotNull(r.getCaseInsensitive("basis", String::class.java))),
                ruleType = TaxRuleRecord.RuleType.valueOf(requireNotNull(r.getCaseInsensitive("rule_type", String::class.java))),
                rate = r.getCaseInsensitive("rate", java.lang.Double::class.java)?.toDouble(),
                annualWageCapCents = r.getCaseInsensitive("annual_wage_cap_cents", java.lang.Long::class.java)?.toLong(),
                bracketsJson = r.getCaseInsensitive("brackets_json", String::class.java),
                standardDeductionCents = r.getCaseInsensitive("standard_deduction_cents", java.lang.Long::class.java)?.toLong(),
                additionalWithholdingCents = r.getCaseInsensitive("additional_withholding_cents", java.lang.Long::class.java)?.toLong(),
                employerId = r.getCaseInsensitive("employer_id", String::class.java)?.let(::EmployerId),
                effectiveFrom = r.getCaseInsensitive("effective_from", LocalDate::class.java),
                effectiveTo = r.getCaseInsensitive("effective_to", LocalDate::class.java),
                filingStatus = r.getCaseInsensitive("filing_status", String::class.java),
                residentStateFilter = r.getCaseInsensitive("resident_state_filter", String::class.java),
                workStateFilter = r.getCaseInsensitive("work_state_filter", String::class.java),
                localityFilter = r.getCaseInsensitive("locality_filter", String::class.java),
                fitVariant = r.getCaseInsensitive("fit_variant", String::class.java),
            )
        }
    }

    private fun parseTaxBasis(raw: String): TaxBasis = when (raw) {
        "Gross" -> TaxBasis.Gross
        "FederalTaxable" -> TaxBasis.FederalTaxable
        "StateTaxable" -> TaxBasis.StateTaxable
        "SocialSecurityWages" -> TaxBasis.SocialSecurityWages
        "MedicareWages" -> TaxBasis.MedicareWages
        "SupplementalWages" -> TaxBasis.SupplementalWages
        "FutaWages" -> TaxBasis.FutaWages
        else -> error("Unknown TaxBasis '$raw' from tax_rule.basis")
    }
}
