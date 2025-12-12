package com.example.uspayroll.tax.config

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Combined validation tests for groups of tax-config JSON files that are
 * commonly imported together into the same catalog/DB in tests.
 *
 * These catch cross-file issues such as duplicate IDs or overlapping effective
 * date ranges for the same key, beyond the per-file validation.
 */
class TaxConfigCombinedValidationTest {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun loadRules(vararg relativeNames: String): List<TaxRuleConfig> = relativeNames.flatMap { name ->
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream("tax-config/$name")) {
            "Missing classpath resource tax-config/$name"
        }
        val json = stream.bufferedReader().use { it.readText() }
        val file: TaxRuleFile = objectMapper.readValue(json)
        file.rules
    }

    @Test
    fun `core 2025 catalog (example federal plus state income) validates as a set`() {
        val rules = loadRules(
            "example-federal-2025.json",
            "state-income-2025.json",
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertTrue(
            result.isValid,
            "Expected example-federal-2025 + state-income-2025 to validate together, but found: " +
                result.errors.joinToString("; ") { e ->
                    val idPart = e.ruleId?.let { "id=$it: " } ?: ""
                    idPart + e.message
                },
        )
    }

    @Test
    fun `core 2025 catalog with Michigan locals validates as a set`() {
        val rules = loadRules(
            "example-federal-2025.json",
            "state-income-2025.json",
            "mi-locals-2025.json",
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertTrue(
            result.isValid,
            "Expected example-federal-2025 + state-income-2025 + mi-locals-2025 to validate together, but found: " +
                result.errors.joinToString("; ") { e ->
                    val idPart = e.ruleId?.let { "id=$it: " } ?: ""
                    idPart + e.message
                },
        )
    }

    @Test
    fun `example federal and employer overlays validate together`() {
        val rules = loadRules(
            "example-federal-2025.json",
            "employer-overlays-2025.json",
        )

        val result = TaxRuleConfigValidator.validateRules(rules)
        assertTrue(
            result.isValid,
            "Expected example-federal-2025 + employer-overlays-2025 to validate together, but found: " +
                result.errors.joinToString("; ") { e ->
                    val idPart = e.ruleId?.let { "id=$it: " } ?: ""
                    idPart + e.message
                },
        )
    }
}
