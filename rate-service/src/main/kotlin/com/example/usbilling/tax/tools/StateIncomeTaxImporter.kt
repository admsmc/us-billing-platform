package com.example.usbilling.tax.tools

import com.example.usbilling.tax.config.TaxRuleFile
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Command-line utility that reads state income tax CSV files for a given year
 * and produces a JSON TaxRuleFile suitable for import into the tax_rule table.
 *
 * Inputs (per year):
 *  - src/main/resources/state-income-tax-YYYY-rules.csv
 *  - src/main/resources/state-income-tax-YYYY-brackets.csv
 *
 * Output:
 *  - src/main/resources/tax-config/state-income-YYYY.json
 */
object StateIncomeTaxImporter {

    @JvmStatic
    fun main(args: Array<String>) {
        val year = args.getOrNull(0) ?: System.getenv("TAX_YEAR") ?: "2025"
        val resourcesDir = TaxContentPaths.resourcesDir()
        Files.createDirectories(resourcesDir)

        val rulesCsv = resourcesDir.resolve("state-income-tax-$year-rules.csv")
        val bracketsCsv = resourcesDir.resolve("state-income-tax-$year-brackets.csv")
        val outputJson = resourcesDir.resolve("tax-config/state-income-$year.json")

        require(Files.exists(rulesCsv)) {
            "Missing rules CSV: $rulesCsv"
        }
        require(Files.exists(bracketsCsv)) {
            "Missing brackets CSV: $bracketsCsv"
        }

        Files.createDirectories(outputJson.parent)

        val rules = Files.newBufferedReader(rulesCsv).use { rulesReader ->
            Files.newBufferedReader(bracketsCsv).use { bracketReader ->
                StateIncomeTaxCsvParser.parse(rulesReader, bracketReader)
            }
        }

        val file = TaxRuleFile(rules = rules)
        writeJson(file, outputJson)
    }

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun writeJson(file: TaxRuleFile, path: Path) {
        val json = objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(file)

        Files.writeString(
            path,
            json,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
