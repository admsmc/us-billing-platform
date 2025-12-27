package com.example.usbilling.worker

import com.example.usbilling.labor.http.LaborStandardsContextDto
import com.example.usbilling.payroll.model.FilingStatus
import com.example.usbilling.payroll.model.TaxJurisdictionType
import com.example.usbilling.shared.EmployerId
import com.example.usbilling.tax.http.TaxBracketDto
import com.example.usbilling.tax.http.TaxContextDto
import com.example.usbilling.tax.http.TaxRuleDto
import com.example.usbilling.tax.http.TaxRuleKindDto
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ContextConfiguration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Worker-level integration test that verifies HttpTaxClient and
 * HttpLaborStandardsClient correctly consume DTO responses and map them back
 * into domain models.
 *
 * This uses MockWebServer to avoid a direct Gradle dependency on tax-service and
 * labor-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ContextConfiguration(initializers = [TaxLaborHttpClientsIntegrationTest.Companion.Initializer::class])
@TestInstance(Lifecycle.PER_CLASS)
class TaxLaborHttpClientsIntegrationTest {

    companion object {
        lateinit var taxServer: MockWebServer
        lateinit var laborServer: MockWebServer

        private val mapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        class Initializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
            override fun initialize(context: ConfigurableApplicationContext) {
                taxServer = MockWebServer().apply {
                    dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val path = request.path.orEmpty()
                            if (!path.startsWith("/employers/") || !path.contains("/tax-context")) {
                                return MockResponse().setResponseCode(404)
                            }

                            val dto = TaxContextDto(
                                federal = listOf(
                                    TaxRuleDto(
                                        id = "US_FIT_SINGLE",
                                        jurisdictionType = TaxJurisdictionType.FEDERAL,
                                        jurisdictionCode = "US",
                                        basis = "FederalTaxable",
                                        kind = TaxRuleKindDto.BRACKETED,
                                        brackets = listOf(TaxBracketDto(upToCents = null, rate = 0.10)),
                                        standardDeductionCents = 12_000_00L,
                                        filingStatus = FilingStatus.SINGLE,
                                    ),
                                ),
                                state = listOf(
                                    TaxRuleDto(
                                        id = "CA_SIT_FLAT",
                                        jurisdictionType = TaxJurisdictionType.STATE,
                                        jurisdictionCode = "CA",
                                        basis = "StateTaxable",
                                        kind = TaxRuleKindDto.FLAT,
                                        rate = 0.05,
                                    ),
                                ),
                            )

                            return MockResponse()
                                .setResponseCode(200)
                                .addHeader("Content-Type", "application/json")
                                .setBody(mapper.writeValueAsString(dto))
                        }
                    }
                }
                taxServer.start()

                laborServer = MockWebServer().apply {
                    dispatcher = object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val path = request.path.orEmpty()
                            if (!path.startsWith("/employers/") || !path.contains("/labor-standards")) {
                                return MockResponse().setResponseCode(404)
                            }

                            val state = request.requestUrl?.queryParameter("state")
                            val dto = when (state) {
                                "CA" -> LaborStandardsContextDto(
                                    federalMinimumWageCents = 1_650L,
                                    federalTippedCashMinimumCents = null,
                                )
                                "TX" -> LaborStandardsContextDto(
                                    federalMinimumWageCents = 7_25L,
                                    federalTippedCashMinimumCents = 2_13L,
                                )
                                else -> LaborStandardsContextDto(
                                    federalMinimumWageCents = 7_25L,
                                    federalTippedCashMinimumCents = null,
                                )
                            }

                            return MockResponse()
                                .setResponseCode(200)
                                .addHeader("Content-Type", "application/json")
                                .setBody(mapper.writeValueAsString(dto))
                        }
                    }
                }
                laborServer.start()

                val taxBase = taxServer.url("").toString().removeSuffix("/")
                val laborBase = laborServer.url("").toString().removeSuffix("/")

                TestPropertyValues.of(
                    "downstreams.tax.base-url=$taxBase",
                    "downstreams.labor.base-url=$laborBase",
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
        taxServer.shutdown()
        laborServer.shutdown()
    }

    @Test
    fun `HttpTaxClient and HttpLaborStandardsClient consume DTOs and map to domain`() {
        val employerId = EmployerId("EMP-WORKER-HTTP-CLIENTS")
        val asOfDate = LocalDate.of(2025, 6, 30)

        val taxContext = taxClient.getTaxContext(
            employerId = employerId,
            asOfDate = asOfDate,
        )

        assertTrue(taxContext.federal.isNotEmpty(), "Expected federal rules from mocked HTTP tax service")
        assertTrue(taxContext.state.isNotEmpty(), "Expected state rules from mocked HTTP tax service")

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
