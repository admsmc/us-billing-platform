package com.example.uspayroll.worker

import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.tax.TaxServiceApplication
import com.example.uspayroll.tax.config.TaxRuleFile
import com.example.uspayroll.tax.persistence.TaxRuleConfigImporter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ContextConfiguration
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Worker-level integration test that boots real tax-service and labor-service
 * over HTTP (using H2 + Flyway) and verifies that HttpTaxClient and
 * HttpLaborStandardsClient correctly consume DTO responses and map them back
 * into domain models.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers = [TaxLaborHttpClientsIntegrationTest.Companion.Initializer::class])
@TestInstance(Lifecycle.PER_CLASS)
class TaxLaborHttpClientsIntegrationTest {

    companion object {
        lateinit var taxContext: ConfigurableApplicationContext
        lateinit var laborContext: ConfigurableApplicationContext

        class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                // Start real tax-service with H2 and import example config.
                taxContext = SpringApplicationBuilder(TaxServiceApplication::class.java)
                    .run(
                        "--server.port=0",
                        // Keep the in-memory DB alive across Flyway + subsequent connections.
                        "--tax.db.url=jdbc:h2:mem:tax_http_worker;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                        "--tax.db.username=sa",
                        "--tax.db.password=",
                        "--spring.flyway.enabled=true",
                        "--spring.flyway.locations=classpath:db/migration/tax",
                    )

                val taxDsl = taxContext.getBean(DSLContext::class.java)
                taxDsl.deleteFrom(DSL.table("tax_rule")).execute()

                val importer = TaxRuleConfigImporter(taxDsl)
                val objectMapper = jacksonObjectMapper()
                    .registerModule(JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

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

                // Start real labor-service with H2 and seed a couple of rows.
                laborContext = SpringApplicationBuilder(com.example.uspayroll.labor.LaborServiceApplication::class.java)
                    .run(
                        "--server.port=0",
                        // Keep the in-memory DB alive across Flyway + subsequent connections.
                        "--labor.db.url=jdbc:h2:mem:labor_http_worker;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                        "--labor.db.username=sa",
                        "--labor.db.password=",
                        "--spring.flyway.enabled=true",
                        "--spring.flyway.locations=classpath:db/migration/labor",
                    )

                val laborDsl = laborContext.getBean(DSLContext::class.java)
                laborDsl.deleteFrom(DSL.table("labor_standard")).execute()

                fun insertLaborStandard(state: String, regularMinCents: Long, tippedMinCents: Long? = null) {
                    laborDsl.insertInto(DSL.table("labor_standard"))
                        .columns(
                            DSL.field("state_code"),
                            DSL.field("effective_from"),
                            DSL.field("effective_to"),
                            DSL.field("regular_minimum_wage_cents"),
                            DSL.field("tipped_minimum_cash_wage_cents"),
                        )
                        .values(
                            state,
                            java.sql.Date.valueOf("2025-01-01"),
                            null,
                            regularMinCents,
                            tippedMinCents,
                        )
                        .execute()
                }

                insertLaborStandard("CA", 1_650L, null)
                insertLaborStandard("TX", 725L, 213L)

                val taxPort = requireNotNull(taxContext.environment.getProperty("local.server.port")) {
                    "Expected tax-service to expose local.server.port"
                }
                val laborPort = requireNotNull(laborContext.environment.getProperty("local.server.port")) {
                    "Expected labor-service to expose local.server.port"
                }

                // Point worker's HTTP clients at the real services.
                TestPropertyValues.of(
                    "tax.base-url=http://localhost:$taxPort",
                    "labor.base-url=http://localhost:$laborPort",
                ).applyTo(context.environment)
            }
        }
    }

    @Autowired
    lateinit var taxClient: com.example.uspayroll.worker.client.TaxClient

    @Autowired
    lateinit var laborClient: com.example.uspayroll.worker.client.LaborStandardsClient

    @AfterAll
    fun tearDown() {
        taxContext.close()
        laborContext.close()
    }

    @Test
    fun `HttpTaxClient and HttpLaborStandardsClient consume DTOs and map to domain`() {
        val employerId = EmployerId("EMP-WORKER-HTTP-CLIENTS")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = taxClient.getTaxContext(
            employerId = employerId,
            asOfDate = asOfDate,
        )

        assertTrue(taxContext.federal.isNotEmpty(), "Expected federal rules from HTTP tax-service")
        assertTrue(taxContext.state.isNotEmpty(), "Expected state rules from HTTP tax-service")

        val laborCa = laborClient.getLaborStandards(
            employerId = employerId,
            asOfDate = asOfDate,
            workState = "CA",
            homeState = "CA",
        )
        val laborTx = laborClient.getLaborStandards(
            employerId = employerId,
            asOfDate = asOfDate,
            workState = "TX",
            homeState = "TX",
        )

        requireNotNull(laborCa)
        requireNotNull(laborTx)

        assertEquals(1_650L, laborCa.federalMinimumWage.amount)
        assertEquals(7_25L, laborTx.federalMinimumWage.amount)
        assertEquals(null, laborCa.federalTippedCashMinimum?.amount)
        assertEquals(2_13L, laborTx.federalTippedCashMinimum?.amount)
    }
}
