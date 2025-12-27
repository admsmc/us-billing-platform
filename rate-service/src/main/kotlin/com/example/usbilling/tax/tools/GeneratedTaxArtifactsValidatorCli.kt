package com.example.usbilling.tax.tools

import com.example.usbilling.tax.config.TaxRuleFile
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Validates that generated tax-config artifacts match what would be produced from
 * their curated CSV inputs.
 */
object GeneratedTaxArtifactsValidatorCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val year = args.getOrNull(0) ?: System.getenv("TAX_YEAR") ?: "2025"

        val resourcesDir = TaxContentPaths.resourcesDir()
        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        val errors = mutableListOf<String>()

        // 1) state-income-tax-YYYY-(rules|brackets).csv -> tax-config/state-income-YYYY.json
        errors += validateStateIncomeTax(resourcesDir, mapper, year)

        // 2) wage-bracket-YYYY-biweekly.csv -> tax-config/federal-YYYY-pub15t-wage-bracket-biweekly.json
        errors += validateFederalWageBracketBiweekly(resourcesDir, mapper, year)

        if (errors.isNotEmpty()) {
            println("Generated tax artifacts are not up to date:")
            errors.forEach { println("  - $it") }
            System.exit(1)
        } else {
            println("Generated tax artifacts up to date for year=$year")
        }
    }

    private fun validateStateIncomeTax(resourcesDir: Path, mapper: com.fasterxml.jackson.databind.ObjectMapper, year: String): List<String> {
        val rulesCsv = resourcesDir.resolve("state-income-tax-$year-rules.csv")
        val bracketsCsv = resourcesDir.resolve("state-income-tax-$year-brackets.csv")
        val outputJson = resourcesDir.resolve("tax-config/state-income-$year.json")

        if (!Files.exists(rulesCsv) || !Files.exists(bracketsCsv) || !Files.exists(outputJson)) {
            // If missing, let the regular validator / build pipeline surface it.
            return emptyList()
        }

        val rules = Files.newBufferedReader(rulesCsv).use { rulesReader ->
            Files.newBufferedReader(bracketsCsv).use { bracketReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketReader)
            }
        }

        val expected = TaxRuleFile(rules = rules)
        val actualJson = Files.readString(outputJson)
        val actual: TaxRuleFile = mapper.readValue(actualJson)

        return if (expected == actual) {
            emptyList()
        } else {
            listOf("state-income-$year.json is out of date; re-run :tax-service:runStateIncomeTaxImporter -PtaxYear=$year")
        }
    }

    private fun validateFederalWageBracketBiweekly(resourcesDir: Path, mapper: com.fasterxml.jackson.databind.ObjectMapper, year: String): List<String> {
        val csvPath = resourcesDir.resolve("wage-bracket-$year-biweekly.csv")
        val outputJson = resourcesDir.resolve("tax-config/federal-$year-pub15t-wage-bracket-biweekly.json")

        if (!Files.exists(csvPath) || !Files.exists(outputJson)) {
            return emptyList()
        }

        val rows = Files.newBufferedReader(csvPath).use { reader ->
            WageBracketCsvParser.parse(reader)
        }

        // Keep parameters consistent with the Gradle task generateFederal2025BiweeklyWageBrackets.
        val rules = WageBracketCsvParser.toTaxRuleConfigs(
            rows = rows,
            jurisdictionType = "FEDERAL",
            jurisdictionCode = "US",
            basis = "FederalTaxable",
            baseIdPrefix = "US_FED_FIT_${year}_PUB15T_WB",
            effectiveFrom = java.time.LocalDate.parse("$year-01-01"),
            effectiveTo = java.time.LocalDate.parse("9999-12-31"),
        )

        val expected = TaxRuleFile(rules = rules)
        val actualJson = Files.readString(outputJson)
        val actual: TaxRuleFile = mapper.readValue(actualJson)

        return if (expected == actual) {
            emptyList()
        } else {
            listOf("federal-$year-pub15t-wage-bracket-biweekly.json is out of date; re-run :tax-service:generateFederalPub15TWageBracketBiweekly -PtaxYear=$year")
        }
    }
}
