package com.example.uspayroll.tax.config

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Safety net tests that load all Git-managed tax config JSON files under
 * src/main/resources/tax-config and validate them with [TaxRuleConfigValidator].
 *
 * The goal is to make it hard to accidentally introduce an invalid config
 * (unknown basis, bad ruleType, invalid locality, overlapping date ranges,
 * etc.) without immediately breaking the test suite.
 */
class TaxConfigFilesValidationTest {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `all tax-config JSON files validate successfully`() {
        val dir: Path = Paths.get("tax-service", "src", "main", "resources", "tax-config")
        require(Files.exists(dir)) { "Expected tax-config directory at $dir" }

        val jsonFiles: List<Path> = Files.list(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                .toList()
        }

        val failures = mutableListOf<String>()

        for (path in jsonFiles.sortedBy { it.fileName.toString() }) {
            val json = Files.readString(path)
            val file: TaxRuleFile = objectMapper.readValue(json)
            val result = TaxRuleConfigValidator.validateFile(file)
            if (!result.isValid) {
                val errorSummary = result.errors.joinToString(
                    separator = "; ",
                    limit = 10,
                ) { err ->
                    val idPart = err.ruleId?.let { "id=$it: " } ?: ""
                    idPart + err.message
                }
                failures += "${path.fileName}: $errorSummary"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Expected all tax-config JSON files to pass validation, but found failures:\n" +
                failures.joinToString(separator = "\n"),
        )
    }
}