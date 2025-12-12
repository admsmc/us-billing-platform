package com.example.uspayroll.labor.tools

import com.example.uspayroll.labor.api.StateLaborStandard
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Test
import java.io.StringReader
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-check tests for labor standards catalogs (CSV and generated JSON).
 *
 * These are analogous to the tax-service catalog validation tests and are
 * intended to make it hard to introduce malformed or inconsistent labor
 * standards content.
 */
class LaborCatalogValidationTest {

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private fun readResourceText(name: String): String {
        val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(name)) {
            "Expected resource '$name' on the classpath (labor-service/src/main/resources/$name)"
        }
        return stream.bufferedReader().use { it.readText() }
    }

    @Test
    fun `labor-standards-2025 csv parses successfully`() {
        val csv = readResourceText("labor-standards-2025.csv")
        val standards = LaborStandardsCsvParser.parse(StringReader(csv))

        assertTrue(standards.isNotEmpty(), "Expected at least one state labor standard from CSV")
        // All state codes should be two-letter uppercase.
        standards.forEach { s ->
            assertTrue(s.stateCode.matches(Regex("^[A-Z]{2}$")), "Invalid stateCode '${'$'}{s.stateCode}' in labor standards CSV")
        }
    }

    @Test
    fun `labor-standards-2025 json matches CSV-derived standards`() {
        val csv = readResourceText("labor-standards-2025.csv")
        val json = readResourceText("labor-standards-2025.json")

        val fromCsv: List<StateLaborStandard> = LaborStandardsCsvParser.parse(StringReader(csv))
        val fromJson: List<StateLaborStandard> = objectMapper.readValue(json)

        // Compare just (stateCode, effectiveFrom, effectiveTo) sets to avoid
        // being over-strict about numeric rounding.
        fun key(s: StateLaborStandard) = Triple(s.stateCode, s.effectiveFrom, s.effectiveTo)

        val csvKeys = fromCsv.map(::key).toSet()
        val jsonKeys = fromJson.filter { it.localityCode == null }.map(::key).toSet()

        assertEquals(csvKeys, jsonKeys, "Mismatch between CSV and JSON statewide labor standards keys")
    }

    @Test
    fun `labor locality codes in JSON are uppercase and scoped by state`() {
        val json = readResourceText("labor-standards-2025.json")
        val standards: List<StateLaborStandard> = objectMapper.readValue(json)

        val localityStandards = standards.filter { it.localityCode != null }
        assertTrue(localityStandards.isNotEmpty(), "Expected at least one locality-specific labor standard in JSON")

        localityStandards.forEach { s ->
            val code = requireNotNull(s.localityCode)
            val kind = requireNotNull(s.localityKind)
            assertTrue(code.matches(Regex("^[A-Z0-9_]+$")), "Invalid localityCode '$code' in labor standards JSON")
            assertTrue(kind.matches(Regex("^[A-Z_]+$")), "Invalid localityKind '$kind' in labor standards JSON")
        }
    }
}
