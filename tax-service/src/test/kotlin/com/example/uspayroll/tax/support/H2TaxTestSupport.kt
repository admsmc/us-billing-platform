package com.example.uspayroll.tax.support

import com.example.uspayroll.payroll.model.TaxBasis
import com.example.uspayroll.payroll.model.TaxJurisdictionType
import com.example.uspayroll.persistence.flyway.FlywaySupport
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.tax.api.TaxQuery
import com.example.uspayroll.tax.config.TaxRuleFile
import com.example.uspayroll.tax.impl.TaxRuleRecord
import com.example.uspayroll.tax.impl.TaxRuleRepository
import com.example.uspayroll.tax.persistence.TaxRuleConfigImporter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.h2.jdbcx.JdbcDataSource
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * Shared H2-based test harness for DB-backed tax catalog tests.
 *
 * Responsibilities:
 * - Create an in-memory H2 database and minimal `tax_rule` table.
 * - Import `TaxRuleFile` JSON config via [TaxRuleConfigImporter].
 * - Provide a simple [TaxRuleRepository] implementation over that table.
 */
object H2TaxTestSupport {

    fun createDslContext(dbName: String = "taxdb"): DSLContext {
        val ds = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1;MODE=PostgreSQL")
            user = "sa"
            password = ""
        }

        // Apply the same Flyway migrations used in production (against Postgres)
        // to this in-memory H2 database running in PostgreSQL compatibility
        // mode. This ensures our H2-backed tests exercise the real DDL.
        FlywaySupport.cleanAndMigrate(
            dataSource = ds,
            "classpath:db/migration",
        )

        return DSL.using(ds, SQLDialect.H2)
    }

    /** Import a TaxRuleFile JSON resource (on the classpath) into the given H2 DSL context. */
    fun importConfigFromResource(dsl: DSLContext, resourcePath: String, loader: ClassLoader) {
        val importer = TaxRuleConfigImporter(dsl)
        val objectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        val stream = requireNotNull(loader.getResourceAsStream(resourcePath)) {
            "Missing test resource $resourcePath"
        }

        val json = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        val file: TaxRuleFile = objectMapper.readValue(json)
        importer.importRules(file.rules)
    }

    /**
     * Minimal [TaxRuleRepository] implementation that reads from the H2 `tax_rule`
     * table using jOOQ. This is intended for tests only.
     */
    class H2TaxRuleRepository(
        private val dsl: DSLContext,
    ) : TaxRuleRepository {

        override fun findRulesFor(query: TaxQuery): List<TaxRuleRecord> {
            val t = DSL.table("tax_rule")

            // Mirror the selection semantics from JooqTaxRuleRepository, but
            // implemented in tests using jOOQ directly.
            val conditions = mutableListOf<org.jooq.Condition>()

            // Employer: generic (NULL) or employer-specific rules.
            conditions += org.jooq.impl.DSL.or(
                org.jooq.impl.DSL.field("employer_id").isNull,
                org.jooq.impl.DSL.field("employer_id").eq(query.employerId.value),
            )

            // Effective dating.
            conditions += org.jooq.impl.DSL.field("effective_from", LocalDate::class.java).le(query.asOfDate)
            conditions += org.jooq.impl.DSL.field("effective_to", LocalDate::class.java).gt(query.asOfDate)

            // Resident state filter: applies only when provided.
            query.residentState?.let { state ->
                conditions += org.jooq.impl.DSL.or(
                    org.jooq.impl.DSL.field("resident_state_filter").isNull,
                    org.jooq.impl.DSL.field("resident_state_filter").eq(state),
                )
            }

            // Work state filter: similar semantics.
            query.workState?.let { state ->
                conditions += org.jooq.impl.DSL.or(
                    org.jooq.impl.DSL.field("work_state_filter").isNull,
                    org.jooq.impl.DSL.field("work_state_filter").eq(state),
                )
            }

            // Localities:
            // - When none are provided, restrict to rules with no locality_filter.
            // - When provided, allow rules with NULL locality_filter or a
            //   locality_filter that matches one of the requested jurisdictions.
            if (query.localJurisdictions.isEmpty()) {
                conditions += org.jooq.impl.DSL.field("locality_filter").isNull
            } else {
                conditions += org.jooq.impl.DSL.or(
                    org.jooq.impl.DSL.field("locality_filter").isNull,
                    org.jooq.impl.DSL.field("locality_filter").`in`(query.localJurisdictions),
                )
            }

            val whereCondition = conditions
                .fold<org.jooq.Condition, org.jooq.Condition?>(null) { acc, c -> acc?.and(c) ?: c }
                ?: org.jooq.impl.DSL.noCondition()

            val records = dsl
                .selectFrom(t)
                .where(whereCondition)
                .fetch()

            return records.map { r ->
                TaxRuleRecord(
                    id = r.get("ID", String::class.java)!!,
                    jurisdictionType = TaxJurisdictionType.valueOf(
                        r.get("JURISDICTION_TYPE", String::class.java)!!,
                    ),
                    jurisdictionCode = r.get("JURISDICTION_CODE", String::class.java)!!,
                    basis = when (val raw = r.get("BASIS", String::class.java)!!) {
                        "Gross" -> TaxBasis.Gross
                        "FederalTaxable" -> TaxBasis.FederalTaxable
                        "StateTaxable" -> TaxBasis.StateTaxable
                        "SocialSecurityWages" -> TaxBasis.SocialSecurityWages
                        "MedicareWages" -> TaxBasis.MedicareWages
                        "SupplementalWages" -> TaxBasis.SupplementalWages
                        "FutaWages" -> TaxBasis.FutaWages
                        else -> error("Unknown TaxBasis '$raw' from tax_rule.basis in H2 test repository")
                    },
                    ruleType = TaxRuleRecord.RuleType.valueOf(r.get("RULE_TYPE", String::class.java)!!),
                    rate = r.get("RATE", Double::class.java),
                    annualWageCapCents = r.get("ANNUAL_WAGE_CAP_CENTS", Long::class.java),
                    bracketsJson = r.get("BRACKETS_JSON", String::class.java),
                    standardDeductionCents = r.get("STANDARD_DEDUCTION_CENTS", Long::class.java),
                    additionalWithholdingCents = r.get("ADDITIONAL_WITHHOLDING_CENTS", Long::class.java),
                    employerId = r.get("EMPLOYER_ID", String::class.java)?.let(::EmployerId),
                    effectiveFrom = r.get("EFFECTIVE_FROM", LocalDate::class.java),
                    effectiveTo = r.get("EFFECTIVE_TO", LocalDate::class.java),
                    filingStatus = r.get("FILING_STATUS", String::class.java),
                    residentStateFilter = r.get("RESIDENT_STATE_FILTER", String::class.java),
                    workStateFilter = r.get("WORK_STATE_FILTER", String::class.java),
                    localityFilter = r.get("LOCALITY_FILTER", String::class.java),
                )
            }
        }
    }
}
