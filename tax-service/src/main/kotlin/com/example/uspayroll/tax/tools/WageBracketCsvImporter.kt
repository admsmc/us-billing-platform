package com.example.uspayroll.tax.tools

import com.example.uspayroll.tax.config.TaxRuleFile
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Command-line utility that reads IRS Pub. 15-T wage-bracket CSV files and
 * produces a JSON TaxRuleFile suitable for import into the tax_rule table.
 *
 * This is analogous to [StateIncomeTaxImporter] but for federal wage-bracket
 * FIT tables. CSVs are expected to be maintained under src/main/resources.
 *
 * Usage (example):
 *   ./gradlew :tax-service:run -q --args="\
 *     --wageBracketCsv=src/main/resources/wage-bracket-2025-biweekly-single.csv \
 *     --output=src/main/resources/tax-config/federal-2025-pub15t-wage-bracket-biweekly.json"
 */
object WageBracketCsvImporter {

    @JvmStatic
    fun main(args: Array<String>) {
        val params = parseArgs(args.toList())

        val projectDir: Path = Paths.get("").toAbsolutePath()
        val resourcesDir = projectDir.resolve("src/main/resources")

        val csvPath = resourcesDir.resolve(params.csvRelativePath)
        require(Files.exists(csvPath)) { "Missing wage-bracket CSV: $csvPath" }

        val outputJson = resourcesDir.resolve(params.outputRelativePath)
        Files.createDirectories(outputJson.parent)

        val rows = Files.newBufferedReader(csvPath).use { reader ->
            WageBracketCsvParser.parse(reader)
        }

        val rules = WageBracketCsvParser.toTaxRuleConfigs(
            rows = rows,
            jurisdictionType = "FEDERAL",
            jurisdictionCode = "US",
            basis = "FederalTaxable",
            baseIdPrefix = params.baseIdPrefix,
            effectiveFrom = params.effectiveFrom,
            effectiveTo = params.effectiveTo,
        )

        val file = TaxRuleFile(rules = rules)
        writeJson(file, outputJson)
    }

    data class Params(
        val csvRelativePath: String,
        val outputRelativePath: String,
        val baseIdPrefix: String,
        val effectiveFrom: java.time.LocalDate,
        val effectiveTo: java.time.LocalDate,
    )

    private fun parseArgs(args: List<String>): Params {
        fun argValue(name: String, default: String? = null): String {
            val prefix = "--${name}="
            val raw = args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
            return raw ?: default ?: error("Missing required argument --$name")
        }

        val csv = argValue("wageBracketCsv")
        val output = argValue("output")
        val baseIdPrefix = argValue("baseIdPrefix", "US_FED_FIT_2025_PUB15T_WB")
        val effectiveFromRaw = argValue("effectiveFrom", "2025-01-01")
        val effectiveToRaw = argValue("effectiveTo", "9999-12-31")

        val effectiveFrom = java.time.LocalDate.parse(effectiveFromRaw)
        val effectiveTo = java.time.LocalDate.parse(effectiveToRaw)

        return Params(
            csvRelativePath = csv,
            outputRelativePath = output,
            baseIdPrefix = baseIdPrefix,
            effectiveFrom = effectiveFrom,
            effectiveTo = effectiveTo,
        )
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
