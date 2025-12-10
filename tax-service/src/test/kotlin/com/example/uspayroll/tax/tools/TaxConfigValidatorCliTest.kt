package com.example.uspayroll.tax.tools

import com.example.uspayroll.tax.config.TaxRuleConfigValidator
import com.example.uspayroll.tax.config.TaxRuleFile
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.assertTrue

/**
 * Lightweight regression test for [TaxConfigValidatorCli] that exercises the
 * same code path used by the CLI for a single config file
 * (local-income-2025.json).
 *
 * We simulate a minimal project layout with a temporary tax-config directory,
 * copy local-income-2025.json into it, and then run the core validation logic
 * as the CLI would. This ensures that the new local-income config is
 * structurally valid and accepted by [TaxRuleConfigValidator].
 */
class TaxConfigValidatorCliTest {

    @Test
    fun `local-income-2025 json passes TaxRuleConfigValidatorCli-style validation`() {
        // Locate the real local-income-2025.json from the classpath.
        val loader = javaClass.classLoader
        val resourcePath = "tax-config/local-income-2025.json"
        val resourceStream = requireNotNull(loader.getResourceAsStream(resourcePath)) {
            "Missing test resource $resourcePath on classpath"
        }

        val json = resourceStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        // Parse once directly as the CLI would.
        val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        val file: TaxRuleFile = mapper.readValue(json)
        val result = TaxRuleConfigValidator.validateFile(file)

        assertTrue(
            result.isValid,
            "Expected local-income-2025.json to pass TaxRuleConfigValidator, but got errors: ${result.errors}",
        )

        // Additionally, simulate the CLI's directory scanning behaviour by
        // writing the JSON to a temporary directory and re-reading it.
        val tempDir: Path = Files.createTempDirectory("tax-config-cli-test-")
        val tempConfigDir = tempDir.resolve("src/main/resources/tax-config")
        Files.createDirectories(tempConfigDir)

        val tempFile = tempConfigDir.resolve("local-income-2025.json")
        Files.writeString(tempFile, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

        val jsonFromDisk = Files.readString(tempFile, StandardCharsets.UTF_8)
        val fileFromDisk: TaxRuleFile = mapper.readValue(jsonFromDisk)
        val diskResult = TaxRuleConfigValidator.validateFile(fileFromDisk)

        assertTrue(
            diskResult.isValid,
            "Expected local-income-2025.json written to disk to pass validation, but got errors: ${diskResult.errors}",
        )
    }
}
