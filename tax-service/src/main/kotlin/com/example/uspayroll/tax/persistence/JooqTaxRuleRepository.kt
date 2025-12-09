package com.example.uspayroll.tax.persistence

import com.example.uspayroll.payroll.model.TaxBasis
import com.example.uspayroll.payroll.model.TaxJurisdictionType
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.tax.api.TaxQuery
import com.example.uspayroll.tax.impl.TaxRuleRecord
import com.example.uspayroll.tax.impl.TaxRuleRepository
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
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

        // Localities: if none are provided, rules with a non-null locality_filter
        // may still be selected depending on how you want to scope locals.
        if (query.localJurisdictions.isNotEmpty()) {
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

        return records.map { r ->
            TaxRuleRecord(
                id = r.get("id", String::class.java)!!,
                jurisdictionType = TaxJurisdictionType.valueOf(r.get("jurisdiction_type", String::class.java)!!),
                jurisdictionCode = r.get("jurisdiction_code", String::class.java)!!,
                basis = parseTaxBasis(r.get("basis", String::class.java)!!),
                ruleType = TaxRuleRecord.RuleType.valueOf(r.get("rule_type", String::class.java)!!),
                rate = r.get("rate", Double::class.java),
                annualWageCapCents = r.get("annual_wage_cap_cents", Long::class.java),
                bracketsJson = r.get("brackets_json", String::class.java),
                standardDeductionCents = r.get("standard_deduction_cents", Long::class.java),
                additionalWithholdingCents = r.get("additional_withholding_cents", Long::class.java),
                employerId = r.get("employer_id", String::class.java)?.let(::EmployerId),
                effectiveFrom = r.get("effective_from", LocalDate::class.java),
                effectiveTo = r.get("effective_to", LocalDate::class.java),
                filingStatus = r.get("filing_status", String::class.java),
                residentStateFilter = r.get("resident_state_filter", String::class.java),
                workStateFilter = r.get("work_state_filter", String::class.java),
                localityFilter = r.get("locality_filter", String::class.java),
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
        else -> error("Unknown TaxBasis '$raw' from tax_rule.basis")
    }
}
