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
 * Tests that enforce simple, consistent naming conventions for tax rule ids and
 * jurisdiction codes across all tax-config JSON files.
 */
class TaxRuleNamingConventionsTest {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun loadAllRules(): List<TaxRuleConfig> {
        val dir: Path = Paths.get("tax-service", "src", "main", "resources", "tax-config")
        require(Files.exists(dir)) { "Expected tax-config directory at $dir" }

        val jsonFiles: List<Path> = Files.list(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                .toList()
        }

        return jsonFiles.flatMap { path ->
            val json = Files.readString(path)
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