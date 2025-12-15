package com.example.uspayroll.tax.tools

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import kotlin.streams.toList

/**
 * Command-line utility that validates *.metadata.json sidecars for curated tax-content
 * artifacts under tax-content/src/main/resources (CSV inputs).
 */
object TaxContentMetadataValidatorCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val resourcesDir = TaxContentPaths.resourcesDir()

        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        val artifacts = mutableListOf<java.nio.file.Path>()

        val csvFiles = Files.list(resourcesDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".csv") }
            .sorted()
            .toList()
        artifacts += csvFiles

        val taxConfigDir = resourcesDir.resolve("tax-config")
        if (Files.exists(taxConfigDir) && Files.isDirectory(taxConfigDir)) {
            val jsonFiles = Files.list(taxConfigDir)
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                .filter { !it.fileName.toString().endsWith(".metadata.json") }
                .sorted()
                .toList()
            artifacts += jsonFiles
        }

        if (artifacts.isEmpty()) {
            println("No curated tax artifacts found in $resourcesDir; nothing to validate.")
            return
        }

        val allErrors = mutableListOf<ContentMetadataValidation.ValidationError>()

        artifacts.forEach { path ->
            val errs = ContentMetadataValidation.validateSidecar(
                mapper = mapper,
                artifactPath = path,
                expectedDomain = "tax",
            )

            if (errs.isEmpty()) {
                println("OK: ${path.fileName}")
            } else {
                println("Metadata errors for: ${path.fileName}")
                errs.forEach { e -> println("  - ${e.message}") }
                allErrors += errs
            }
        }

        if (allErrors.isNotEmpty()) {
            println("\nMetadata validation FAILED: ${allErrors.size} error(s) across ${artifacts.size} file(s).")
            System.exit(1)
        } else {
            println("\nMetadata validation succeeded: ${artifacts.size} file(s) checked, no errors.")
        }
    }
}
