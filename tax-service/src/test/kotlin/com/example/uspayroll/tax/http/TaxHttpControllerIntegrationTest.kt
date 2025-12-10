package com.example.uspayroll.tax.http

import com.example.uspayroll.payroll.model.TaxContext
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.tax.api.TaxContextProvider
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
        fun taxContextProvider(): TaxContextProvider =
            object : TaxContextProvider {
                override fun getTaxContext(
                    employerId: EmployerId,
                    asOfDate: LocalDate,
                ): TaxContext {
                    // Return an empty context for testing serialization & wiring.
                    return TaxContext()
                }
            }
    }
}
