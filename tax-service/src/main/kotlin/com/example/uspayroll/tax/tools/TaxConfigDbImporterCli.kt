package com.example.uspayroll.tax.tools

import com.example.uspayroll.tax.persistence.TaxRuleConfigImporter
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Files
import java.nio.file.Path

/**
 * Import curated tax-config JSON files into Postgres `tax_rule`.
 *
 * Intended for local/dev benchmark seeding.
 *
 * Expected env vars:
 * - TAX_DB_URL (jdbc:postgresql://host:port/db)
 * - TAX_DB_USERNAME
 * - TAX_DB_PASSWORD
 *
 * Optional env vars:
 * - TAX_CONFIG_DIR (default: tax-content/src/main/resources/tax-config)
 * - TAX_CONFIG_FILES (comma-separated list of filenames/paths to import)
 * - TAX_IMPORT_TRUNCATE (true/false)
 */
object TaxConfigDbImporterCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val year = args.getOrNull(0)
            ?: System.getenv("TAX_YEAR")
            ?: "2025"

        val truncate = args.getOrNull(1)?.toBooleanStrictOrNull()
            ?: System.getenv("TAX_IMPORT_TRUNCATE")?.toBooleanStrictOrNull()
            ?: false

        val dbUrl = System.getenv("TAX_DB_URL")
            ?: error("Missing TAX_DB_URL (expected e.g. jdbc:postgresql://localhost:15432/us_payroll_tax)")

        val dbUsername = System.getenv("TAX_DB_USERNAME")
            ?: error("Missing TAX_DB_USERNAME")

        val dbPassword = System.getenv("TAX_DB_PASSWORD")
            ?: error("Missing TAX_DB_PASSWORD")

        val configDir = Path.of(
            System.getenv("TAX_CONFIG_DIR")
                ?: "tax-content/src/main/resources/tax-config",
        )

        val explicitFiles = System.getenv("TAX_CONFIG_FILES")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        val filesToImport = if (explicitFiles.isNotEmpty()) {
            explicitFiles.map { raw ->
                val path = Path.of(raw)
                if (path.isAbsolute) path else configDir.resolve(raw)
            }
        } else {
            listOf(
                configDir.resolve("federal-$year-pub15t-wage-bracket-biweekly.json"),
                configDir.resolve("federal-payroll-$year.json"),
                configDir.resolve("state-income-$year.json"),
                configDir.resolve("local-income-$year.json"),
            )
        }

        for (p in filesToImport) {
            require(Files.exists(p)) { "Missing tax config file: $p" }
        }

        val ds = PGSimpleDataSource().apply {
            setURL(dbUrl)
            user = dbUsername
            password = dbPassword
        }

        val dsl = DSL.using(ds, SQLDialect.POSTGRES)

        if (truncate) {
            println("[tax-import] Truncating tax_rule...")
            dsl.execute("TRUNCATE TABLE tax_rule")
        }

        val importer = TaxRuleConfigImporter(dsl)

        println("[tax-import] Importing ${filesToImport.size} tax-config file(s) into tax_rule (year=$year) ...")
        for (p in filesToImport) {
            println("[tax-import] Importing: $p")
            importer.importFile(p)
        }

        val count = dsl.fetchValue("select count(*) from tax_rule", Long::class.java) ?: -1L
        println("[tax-import] Done. tax_rule row count=$count")
    }
}
