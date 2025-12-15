package com.example.uspayroll.tax.config

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
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
        val resolver = PathMatchingResourcePatternResolver()
        val resources = resolver.getResources("classpath*:tax-config/*.json")
            .filterNot { it.filename.orEmpty().endsWith(".metadata.json") }

        val failures = mutableListOf<String>()

        for (resource in resources.sortedBy { it.filename.orEmpty() }) {
            val json = resource.inputStream.bufferedReader().use { it.readText() }
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
                failures += "${resource.filename}: $errorSummary"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Expected all tax-config JSON files to pass validation, but found failures:\n" +
                failures.joinToString(separator = "\n"),
        )
    }
}
