package com.example.uspayroll.labor.tools

import com.example.uspayroll.labor.api.StateLaborStandard
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.LocalDate

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

        writeJson(combined, jsonPath)
        writeSql(combined, sqlPath)
    }

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun writeJson(standards: List<StateLaborStandard>, path: Path) {
        val json = objectMapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsString(standards)

        Files.writeString(
            path,
            json,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun writeSql(standards: List<StateLaborStandard>, path: Path) {
        val sb = StringBuilder()
        sb.appendLine("-- Generated labor_standard rows; year=${'$'}{LocalDate.now().year}")

        standards.forEach { standard ->
            sb.appendLine(buildInsert(standard))
            sb.appendLine()
        }

        Files.writeString(
            path,
            sb.toString(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun buildInsert(s: StateLaborStandard): String {
        fun sqlDate(d: LocalDate?): String = d?.let { "DATE '${'$'}it'" } ?: "NULL"
        fun sqlLong(v: Long?): String = v?.toString() ?: "NULL"
        fun sqlDouble(v: Double?): String = v?.toString() ?: "NULL"
        fun sqlString(v: String?): String = v?.let { "'${'$'}it'" } ?: "NULL"

        return """
            |INSERT INTO labor_standard (
            |    state_code,
            |    locality_code,
            |    locality_kind,
            |    effective_from,
            |    effective_to,
            |    regular_minimum_wage_cents,
            |    tipped_minimum_cash_wage_cents,
            |    max_tip_credit_cents,
            |    weekly_ot_threshold_hours,
            |    daily_ot_threshold_hours,
            |    daily_dt_threshold_hours
            |) VALUES (
            |    '${'$'}{s.stateCode}',
            |    ${'$'}{sqlString(s.localityCode)},
            |    ${'$'}{sqlString(s.localityKind)},
            |    ${'$'}{sqlDate(s.effectiveFrom)},
            |    ${'$'}{sqlDate(s.effectiveTo)},
            |    ${'$'}{sqlLong(s.regularMinimumWageCents)},
            |    ${'$'}{sqlLong(s.tippedMinimumCashWageCents)},
            |    ${'$'}{sqlLong(s.maxTipCreditCents)},
            |    ${'$'}{sqlDouble(s.weeklyOvertimeThresholdHours)},
            |    ${'$'}{sqlDouble(s.dailyOvertimeThresholdHours)},
            |    ${'$'}{sqlDouble(s.dailyDoubleTimeThresholdHours)}
            |);
        """.trimMargin()
    }
}
