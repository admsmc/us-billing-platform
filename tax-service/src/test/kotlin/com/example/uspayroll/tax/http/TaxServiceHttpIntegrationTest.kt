package com.example.uspayroll.tax.http

import com.example.uspayroll.tax.config.TaxRuleFile
import com.example.uspayroll.tax.persistence.TaxRuleConfigImporter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end HTTP integration tests for tax-service.
 *
 * These tests run against an in-memory H2 database in PostgreSQL mode with
 * Flyway migrations applied on startup, then import JSON tax config via
 * [TaxRuleConfigImporter] and hit the HTTP endpoint.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "tax.db.url=jdbc:h2:mem:tax_http;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "tax.db.username=sa",
        "tax.db.password=",
    ],
)
@AutoConfigureMockMvc
class TaxServiceHttpIntegrationTest {

    @Autowired
    lateinit var dsl: DSLContext

    @Autowired
    lateinit var mockMvc: MockMvc

    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    @BeforeEach
    fun setupData() {
        // Clean any existing data and import a small set of tax rules from JSON
        // config into the H2-backed tax_rule table.
        dsl.deleteFrom(org.jooq.impl.DSL.table("tax_rule")).execute()

        val importer = TaxRuleConfigImporter(dsl)

        fun importResource(path: String) {
            val stream = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
                "Missing test resource $path"
            }
            val json = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val file: TaxRuleFile = objectMapper.readValue(json)
            importer.importRules(file.rules)
        }

        importResource("tax-config/example-federal-2025.json")
        importResource("tax-config/state-income-2025.json")
    }

    @Test
    fun `GET tax-context returns DTO backed by DB rules`() {
        val employerId = "EMP-TAX-HTTP-INT"
        val asOf = "2025-06-30"

        val mvcResult = mockMvc.get("/employers/$employerId/tax-context") {
            param("asOf", asOf)
        }.andExpect {
            status { isOk() }
            jsonPath("$.federal") { isArray() }
            jsonPath("$.state") { isArray() }
            jsonPath("$.local") { isArray() }
            jsonPath("$.employerSpecific") { isArray() }
        }.andReturn()

        val body = mvcResult.response.contentAsString
        val dto: TaxContextDto = objectMapper.readValue(body)

        // Federal FIT SINGLE rule should be present with 4 brackets and top rate 24%.
        val single = dto.federal.firstOrNull { it.id == "US_FED_FIT_2025_SINGLE" }
            ?: error("Expected US_FED_FIT_2025_SINGLE in federal rules from HTTP endpoint")

        assertEquals(TaxRuleKindDto.BRACKETED, single.kind)
        assertEquals(4, single.brackets.size, "SINGLE should have 4 brackets over HTTP")
        assertEquals(0.10, single.brackets.first().rate, 1e-9, "First SINGLE bracket rate should be 10% over HTTP")
        assertEquals(0.24, single.brackets.last().rate, 1e-9, "Top SINGLE bracket rate should be 24% over HTTP")

        // CA state income tax should have multiple brackets with top rate 12.3%.
        val caState = dto.state
            .filter { it.jurisdictionCode == "CA" && it.kind == TaxRuleKindDto.BRACKETED }
            .firstOrNull { it.brackets.isNotEmpty() }
            ?: error("Expected at least one CA BRACKETED state rule from HTTP endpoint")

        assertTrue(caState.brackets.size >= 5, "Expected multiple CA brackets over HTTP")
        assertEquals(0.123, caState.brackets.last().rate, 1e-9, "Top CA state rate should be 12.3% over HTTP")
    }

    @Test
    fun `GET tax-context honors locality query parameter for local rules`() {
        val employerId = "EMP-TAX-HTTP-INT-LOCAL"
        val asOf = "2025-01-15"

        mockMvc.get("/employers/$employerId/tax-context") {
            param("asOf", asOf)
            param("residentState", "NY")
            param("workState", "NY")
            param("locality", "NYC")
        }.andExpect {
            status { isOk() }
            jsonPath("$.local") { isArray() }
        }
    }
}
