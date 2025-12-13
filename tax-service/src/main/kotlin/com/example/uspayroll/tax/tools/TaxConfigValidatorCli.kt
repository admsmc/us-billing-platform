package com.example.uspayroll.tax.tools

import com.example.uspayroll.tax.config.TaxRuleConfigValidator
import com.example.uspayroll.tax.config.TaxRuleFile
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files

/**
 * Command-line utility that validates all TaxRuleFile JSON documents under
 * `src/main/resources/tax-config`. Intended to be wired as a Gradle task and CI
 * check.
 */
object TaxConfigValidatorCli {

    @JvmStatic
    fun main(args: Array<String>) {
        val resourcesDir = TaxContentPaths.resourcesDir()
        val taxConfigDir = resourcesDir.resolve("tax-config")

        if (!Files.exists(taxConfigDir)) {
            println("No tax-config directory found at $taxConfigDir; nothing to validate.")
            return
        }

        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        var totalFiles = 0
        val allErrors = mutableListOf<TaxRuleConfigValidator.ValidationError>()

        Files.list(taxConfigDir)
            .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
            .sorted()
            .forEach { path ->
                totalFiles += 1
                val json = Files.readString(path)
                val file: TaxRuleFile = mapper.readValue(json)

                val result = TaxRuleConfigValidator.validateFile(file)
                if (!result.isValid) {
                    println("Validation errors in config file: ${path.fileName}")
                    result.errors.forEach { err ->
                        val idPart = err.ruleId?.let { "[id=$it] " } ?: ""
                        println("  - ${idPart}${err.message}")
                    }
                    allErrors += result.errors
                } else {
                    println("OK: ${path.fileName} (${file.rules.size} rules)")
                }
            }

        if (totalFiles == 0) {
            println("No *.json files found in $taxConfigDir; nothing to validate.")
            return
        }

        if (allErrors.isNotEmpty()) {
            println("\nValidation FAILED: ${allErrors.size} error(s) across $totalFiles file(s).")
            System.exit(1)
        } else {
            println("\nValidation succeeded: $totalFiles file(s) checked, no errors.")
        }
    }
}
