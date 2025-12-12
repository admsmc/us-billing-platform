package com.example.uspayroll.tax.config

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests to ensure locality codes are consistent between the validator registry
 * and the actual tax-config JSON files.
 */
class TaxLocalityRegistryTest {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun loadAllLocalityFilters(): Set<String> {
        val resolver = PathMatchingResourcePatternResolver()
        val resources = resolver.getResources("classpath*:tax-config/*.json")

        val filters = mutableSetOf<String>()
        resources.forEach { resource ->
            val json = resource.inputStream.bufferedReader().use { it.readText() }
            val file: TaxRuleFile = objectMapper.readValue(json)
            file.rules.mapNotNullTo(filters) { it.localityFilter }
        }
        return filters
    }

    @Test
    fun `every localityFilter used in configs is known to validator`() {
        val localitiesInConfig = loadAllLocalityFilters()

        // This list must stay in sync with TaxRuleConfigValidator.validLocalities.
        val validLocalities = setOf(
            "NYC",
            "DETROIT",
            "GRAND_RAPIDS",
            "LANSING",
            "PHILADELPHIA",
            "ST_LOUIS",
            "KANSAS_CITY",
            "COLUMBUS",
            "CLEVELAND",
            "CINCINNATI",
            "AKRON",
            "DAYTON",
            "TOLEDO",
            "YOUNGSTOWN",
            "BALTIMORE_CITY",
            "BIRMINGHAM",
            "WILMINGTON",
            "MARION_COUNTY",
            "LOUISVILLE",
            "PORTLAND_METRO_SHS",
            "MULTNOMAH_PFA",
        )

        assertTrue(
            localitiesInConfig.all { it in validLocalities },
            "Found localityFilter values not present in TaxRuleConfigValidator registry: " +
                (localitiesInConfig - validLocalities),
        )
    }

    @Test
    fun `every locality in validator registry is exercised by at least one config`() {
        val localitiesInConfig = loadAllLocalityFilters()

        val validLocalities = setOf(
            "NYC",
            "DETROIT",
            "GRAND_RAPIDS",
            "LANSING",
            "PHILADELPHIA",
            "ST_LOUIS",
            "KANSAS_CITY",
            "COLUMBUS",
            "CLEVELAND",
            "CINCINNATI",
            "AKRON",
            "DAYTON",
            "TOLEDO",
            "YOUNGSTOWN",
            "BALTIMORE_CITY",
            "BIRMINGHAM",
            "WILMINGTON",
            "MARION_COUNTY",
            "LOUISVILLE",
            "PORTLAND_METRO_SHS",
            "MULTNOMAH_PFA",
        )

        val unused = validLocalities - localitiesInConfig
        assertEquals(
            emptySet<String>(),
            unused,
            "Expected every locality in validator registry to appear in at least one config, but missing: ${'$'}unused",
        )
    }
}
