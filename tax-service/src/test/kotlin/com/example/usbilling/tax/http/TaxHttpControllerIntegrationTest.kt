package com.example.usbilling.tax.http

import com.example.usbilling.payroll.model.TaxBasis
import com.example.usbilling.payroll.model.TaxJurisdiction
import com.example.usbilling.payroll.model.TaxJurisdictionType
import com.example.usbilling.payroll.model.TaxRule
import com.example.usbilling.tax.api.TaxCatalog
import com.example.usbilling.tax.api.TaxQuery
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate

/**
 * Web MVC slice tests for [TaxHttpController], including basic payload shape
 * and X-Correlation-ID behavior via [CorrelationIdFilter].
 */
@WebMvcTest(controllers = [TaxHttpController::class])
@Import(TaxHttpControllerIntegrationTest.TestConfig::class, CorrelationIdFilter::class)
class TaxHttpControllerIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `GET tax-context returns context and echoes correlation ID`() {
        val employerId = "EMP-TAX-HTTP-1"
        val asOf = LocalDate.of(2025, 1, 15)
        val correlationId = "corr-tax-123"

        mockMvc.get("/employers/$employerId/tax-context") {
            param("asOf", asOf.toString())
            header("X-Correlation-ID", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.federal") { isArray() }
            jsonPath("$.state") { isArray() }
            jsonPath("$.local") { isArray() }
            jsonPath("$.employerSpecific") { isArray() }
            header { string("X-Correlation-ID", correlationId) }
        }
    }

    @TestConfiguration
    class TestConfig {

        @Bean
        fun taxCatalog(): TaxCatalog = object : TaxCatalog {
            override fun loadRules(query: TaxQuery): List<TaxRule> {
                // Return a single synthetic federal rule to exercise
                // serialization and wiring without hitting a database.
                return listOf(
                    TaxRule.FlatRateTax(
                        id = "FED_TEST",
                        jurisdiction = TaxJurisdiction(
                            type = TaxJurisdictionType.FEDERAL,
                            code = "US",
                        ),
                        basis = TaxBasis.Gross,
                        rate = com.example.uspayroll.payroll.model.Percent(0.1),
                    ),
                )
            }
        }
    }
}
