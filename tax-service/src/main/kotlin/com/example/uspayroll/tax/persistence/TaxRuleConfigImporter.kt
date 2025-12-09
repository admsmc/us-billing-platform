package com.example.uspayroll.tax.persistence

import com.example.uspayroll.tax.config.TaxBracketConfig
import com.example.uspayroll.tax.config.TaxRuleConfig
import com.example.uspayroll.tax.config.TaxRuleConfigValidator
import com.example.uspayroll.tax.config.TaxRuleFile
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.jooq.impl.DSL
import java.nio.file.Files
import java.nio.file.Path

/**
 * Utility responsible for loading Git-managed tax rule configuration files and
 * inserting corresponding rows into the `tax_rule` table.
 *
 * This class is intentionally not tied to Spring so it can be used from
 * migrations, command-line tools, or tests.
 */
class TaxRuleConfigImporter(
    private val dsl: DSLContext,
) {

    private val logger = LoggerFactory.getLogger(TaxRuleConfigImporter::class.java)

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /**
     * Load a single config file from disk and insert its rules.
     */
    fun importFile(path: Path) {
        val json = Files.readString(path)
        val file: TaxRuleFile = objectMapper.readValue(json)
        importRules(file.rules)
    }

    /**
     * Import all JSON config files from the given directory (non-recursive).
     */
    fun importDirectory(dir: Path) {
        Files.list(dir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
            .forEach { importFile(it) }
    }

    /**
     * Insert the given rules into the `tax_rule` table as SCD2 rows. This
     * implementation is append-only: it does not update or delete existing
     * rows, so callers must ensure effective date ranges do not overlap in an
     * unintended way.
     */
    fun importRules(rules: List<TaxRuleConfig>) {
        if (rules.isEmpty()) return

        val startNanos = System.nanoTime()
        logger.info("Importing {} tax rule(s) into tax_rule table", rules.size)

        val validation = TaxRuleConfigValidator.validateRules(rules)
        if (!validation.isValid) {
            val details = validation.errors.joinToString(separator = "; ") { err ->
                val idPart = err.ruleId?.let { "id=$it: " } ?: ""
                idPart + err.message
            }
            logger.error("Tax rule config validation failed: {}", details)
        }
        require(validation.isValid) {
            val details = validation.errors.joinToString(separator = "; ") { err ->
                val idPart = err.ruleId?.let { "id=$it: " } ?: ""
                idPart + err.message
            }
            "Tax rule config validation failed: $details"
        }

        val t = DSL.table("tax_rule")

        dsl.transaction { _ ->
            rules.forEach { rule ->
                val bracketsJson = rule.brackets?.let { serializeBrackets(it) }

                dsl.insertInto(t)
                    .columns(
                        DSL.field("id"),
                        DSL.field("employer_id"),
                        DSL.field("jurisdiction_type"),
                        DSL.field("jurisdiction_code"),
                        DSL.field("basis"),
                        DSL.field("rule_type"),
                        DSL.field("rate"),
                        DSL.field("annual_wage_cap_cents"),
                        DSL.field("brackets_json"),
                        DSL.field("standard_deduction_cents"),
                        DSL.field("additional_withholding_cents"),
                        DSL.field("effective_from"),
                        DSL.field("effective_to"),
                        DSL.field("filing_status"),
                        DSL.field("resident_state_filter"),
                        DSL.field("work_state_filter"),
                        DSL.field("locality_filter"),
                    )
                    .values(
                        rule.id,
                        rule.employerId,
                        rule.jurisdictionType,
                        rule.jurisdictionCode,
                        rule.basis,
                        rule.ruleType,
                        rule.rate,
                        rule.annualWageCapCents,
                        bracketsJson,
                        rule.standardDeductionCents,
                        rule.additionalWithholdingCents,
                        rule.effectiveFrom,
                        rule.effectiveTo,
                        rule.filingStatus,
                        rule.residentStateFilter,
                        rule.workStateFilter,
                        rule.localityFilter,
                    )
                    .execute()
            }
        }

        val durationMillis = (System.nanoTime() - startNanos) / 1_000_000
        logger.info(
            "Imported {} tax rule(s) into tax_rule table in {} ms",
            rules.size,
            durationMillis,
        )
    }

    private fun serializeBrackets(brackets: List<TaxBracketConfig>): String =
        objectMapper.writeValueAsString(brackets)
}
