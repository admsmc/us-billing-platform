package com.example.uspayroll.tax.support

import com.example.uspayroll.payroll.model.TaxBasis
import com.example.uspayroll.payroll.model.TaxJurisdictionType
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.tax.api.TaxQuery
import com.example.uspayroll.tax.impl.TaxRuleRecord
import com.example.uspayroll.tax.impl.TaxRuleRepository
import com.example.uspayroll.tax.config.TaxRuleFile
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

        val dsl = DSL.using(ds, SQLDialect.H2)

        // Minimal tax_rule table matching the columns used by TaxRuleConfigImporter.
        dsl.execute(
            """
            DROP TABLE IF EXISTS tax_rule;
            CREATE TABLE tax_rule (
                id                          VARCHAR(100) NOT NULL,
                employer_id                 VARCHAR(64)      NULL,
                jurisdiction_type           VARCHAR(20)      NOT NULL,
                jurisdiction_code           VARCHAR(32)      NOT NULL,
                basis                       VARCHAR(32)      NOT NULL,
                rule_type                   VARCHAR(16)      NOT NULL,
                rate                        DOUBLE PRECISION     NULL,
                annual_wage_cap_cents       BIGINT               NULL,
                brackets_json               CLOB                 NULL,
                standard_deduction_cents    BIGINT               NULL,
                additional_withholding_cents BIGINT              NULL,
                effective_from              DATE            NOT NULL,
                effective_to                DATE            NOT NULL,
                filing_status               VARCHAR(32)     NULL,
                resident_state_filter       VARCHAR(2)      NULL,
                work_state_filter           VARCHAR(2)      NULL,
                locality_filter             VARCHAR(32)     NULL
            );
            """.trimIndent()
        )

        return dsl
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

            val records = dsl
                .selectFrom(t)
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
