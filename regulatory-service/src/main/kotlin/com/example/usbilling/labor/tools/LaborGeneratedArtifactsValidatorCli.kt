package com.example.usbilling.labor.tools

import com.example.usbilling.labor.api.StateLaborStandard
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Validates that generated labor artifacts (JSON + SQL) match what would be
 * produced from the CSV inputs.
 */
object LaborGeneratedArtifactsValidatorCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val year = args.getOrNull(0) ?: System.getenv("LABOR_YEAR") ?: "2025"

        val resourcesDir = resolveResourcesDir()
        val csvPath = resourcesDir.resolve("labor-standards-$year.csv")
        val localCsvPath = resourcesDir.resolve("labor-standards-$year-local.csv")
        val jsonPath = resourcesDir.resolve("labor-standards-$year.json")
        val sqlPath = resourcesDir.resolve("labor-standard-$year.sql")

        require(Files.exists(csvPath)) { "Missing CSV: $csvPath" }
        require(Files.exists(jsonPath)) { "Missing JSON: $jsonPath" }
        require(Files.exists(sqlPath)) { "Missing SQL: $sqlPath" }

        val stateStandards: List<StateLaborStandard> = Files.newBufferedReader(csvPath).use { reader ->
            LaborStandardsCsvParser.parse(reader)
        }

        val localStandards: List<StateLaborStandard> = if (Files.exists(localCsvPath)) {
            Files.newBufferedReader(localCsvPath).use { reader ->
                LaborStandardsCsvParser.parseLocal(reader)
            }
        } else {
            emptyList()
        }

        val combined = stateStandards + localStandards

        val expectedJson = LaborStandardsArtifactRenderer.renderJson(combined)
        val expectedSql = LaborStandardsArtifactRenderer.renderSql(year, combined)

        val actualJson = Files.readString(jsonPath)
        val actualSql = Files.readString(sqlPath)

        val errors = mutableListOf<String>()
        if (actualJson != expectedJson) {
            errors += "labor-standards-$year.json is out of date; re-run :labor-service:runLaborStandardsImporter -PlaborYear=$year"
        }
        if (actualSql != expectedSql) {
            errors += "labor-standard-$year.sql is out of date; re-run :labor-service:runLaborStandardsImporter -PlaborYear=$year"
        }

        if (errors.isNotEmpty()) {
            println("Generated labor artifacts are not up to date:")
            errors.forEach { println("  - $it") }
            System.exit(1)
        } else {
            println("Generated labor artifacts up to date for year=$year")
        }
    }

    private fun resolveResourcesDir(): Path {
        val cwd = Paths.get("").toAbsolutePath()

        val shared = cwd.resolve("labor-service/src/main/resources")
        if (Files.exists(shared)) return shared

        val local = cwd.resolve("src/main/resources")
        if (Files.exists(local)) return local

        var p: Path? = cwd
        repeat(6) {
            val candidate = p?.resolve("labor-service/src/main/resources")
            if (candidate != null && Files.exists(candidate)) return candidate
            p = p?.parent
        }

        return shared
    }
}
