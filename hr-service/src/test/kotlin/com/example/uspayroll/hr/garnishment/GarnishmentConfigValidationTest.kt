package com.example.uspayroll.hr.garnishment

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
 * Safety-net test that loads the HR-service garnishment config JSON files
 * (garnishment-rules.json and garnishment-levy-bands.json) and validates
 * them with [GarnishmentConfigValidator].
 *
 * This mirrors the tax-service config validation tests and is meant to make
 * it hard to introduce malformed or inconsistent garnishment config without
 * immediately breaking the test suite.
 */
class GarnishmentConfigValidationTest {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `garnishment rules and levy bands validate successfully`() {
        val resourcesDir: Path = Paths.get("hr-service", "src", "main", "resources")
        require(Files.exists(resourcesDir)) { "Expected resources directory at $resourcesDir" }

        val rulesPath = resourcesDir.resolve("garnishment-rules.json")
        val bandsPath = resourcesDir.resolve("garnishment-levy-bands.json")

        require(Files.exists(rulesPath)) { "Expected garnishment-rules.json at $rulesPath" }
        require(Files.exists(bandsPath)) { "Expected garnishment-levy-bands.json at $bandsPath" }

        val rulesJson = Files.readString(rulesPath)
        val rules: List<GarnishmentConfigValidator.RuleJson> = objectMapper.readValue(rulesJson)

        val bandsJson = Files.readString(bandsPath)
        val levyConfigs: List<GarnishmentConfigValidator.LevyConfigJson> = objectMapper.readValue(bandsJson)

        val result = GarnishmentConfigValidator.validate(rules, levyConfigs)

        val summary = result.errors.joinToString(separator = "\n") { err ->
            " - ${err.message}"
        }

        assertTrue(
            result.isValid,
            "Expected garnishment config files to validate successfully, but found errors:\n$summary",
        )
    }
}
