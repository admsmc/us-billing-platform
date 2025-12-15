package com.example.uspayroll.tax.config

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertTrue

/**
 * Tests that enforce simple, consistent naming conventions for tax rule ids and
 * jurisdiction codes across all tax-config JSON files.
 */
class TaxRuleNamingConventionsTest {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun loadAllRules(): List<TaxRuleConfig> {
        val resolver = PathMatchingResourcePatternResolver()
        val resources = resolver.getResources("classpath*:tax-config/*.json")
            .filterNot { it.filename.orEmpty().endsWith(".metadata.json") }

        return resources.flatMap { resource ->
            val json = resource.inputStream.bufferedReader().use { it.readText() }
            val file: TaxRuleFile = objectMapper.readValue(json)
            file.rules
        }
    }

    @Test
    fun `rule ids and jurisdiction codes use uppercase underscore convention`() {
        val rules = loadAllRules()

        val idRegex = Regex("^[A-Z0-9_]+")
        val codeRegex = Regex("^[A-Z0-9_]+$")

        rules.forEach { rule ->
            assertTrue(
                idRegex.matches(rule.id),
                "Rule id '${'$'}{rule.id}' must be uppercase with digits/underscores only",
            )
            assertTrue(
                codeRegex.matches(rule.jurisdictionCode),
                "jurisdictionCode '${'$'}{rule.jurisdictionCode}' must be uppercase with digits/underscores only",
            )
        }
    }
}
