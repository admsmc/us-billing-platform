package com.example.usbilling.labor.tools

import com.example.usbilling.labor.api.StateLaborStandard
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * Command-line utility that reads a labor-standards CSV for a given year and
 * produces:
 *  - a JSON configuration file (array of StateLaborStandard objects), and
 *  - a SQL file with INSERT statements for the labor_standard table.
 *
 * This is intended to be run manually or from automation, not wired into the
 * runtime services.
 */
object LaborStandardsImporter {

    @JvmStatic
    fun main(args: Array<String>) {
        val year = args.getOrNull(0) ?: System.getenv("LABOR_YEAR") ?: "2025"

        // When run via Gradle's JavaExec task in the labor-service module,
        // the working directory is the module directory. Use src/main/resources
        // relative to that directory so this works both from IDEs and CLI.
        val projectDir: Path = Paths.get("").toAbsolutePath()
        val resourcesDir = projectDir.resolve("src/main/resources")
        Files.createDirectories(resourcesDir)

        val csvPath = resourcesDir.resolve("labor-standards-$year.csv")
        val localCsvPath = resourcesDir.resolve("labor-standards-$year-local.csv")
        val jsonPath = resourcesDir.resolve("labor-standards-$year.json")
        val sqlPath = resourcesDir.resolve("labor-standard-$year.sql")

        require(Files.exists(csvPath)) {
            "CSV file not found: $csvPath. Expected labor-standards-$year.csv in labor-service/src/main/resources."
        }

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

        Files.writeString(
            jsonPath,
            LaborStandardsArtifactRenderer.renderJson(combined),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        Files.writeString(
            sqlPath,
            LaborStandardsArtifactRenderer.renderSql(year, combined),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }
}
