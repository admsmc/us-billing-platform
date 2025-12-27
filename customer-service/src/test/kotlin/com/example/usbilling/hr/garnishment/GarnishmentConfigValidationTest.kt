package com.example.usbilling.hr.garnishment

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import java.io.InputStream
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

    private fun readResource(name: String): String {
        val stream: InputStream = requireNotNull(this::class.java.classLoader.getResourceAsStream(name)) {
            "Expected $name on test classpath"
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @Test
    fun `garnishment rules and levy bands validate successfully`() {
        val rulesJson = readResource("garnishment-rules.json")
        val rules: List<GarnishmentConfigValidator.RuleJson> = objectMapper.readValue(rulesJson)

        val bandsJson = readResource("garnishment-levy-bands.json")
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
