package com.example.uspayroll.labor.http

import com.example.uspayroll.labor.LaborHttpController
import com.example.uspayroll.labor.api.LaborStandardsContextProvider
import com.example.uspayroll.labor.web.CorrelationIdFilter
import com.example.uspayroll.payroll.model.LaborStandardsContext
import com.example.uspayroll.shared.EmployerId
import com.example.uspayroll.shared.Money
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
 * Web MVC slice tests for [LaborHttpController], including basic payload shape
 * and X-Correlation-ID behavior via [CorrelationIdFilter].
 */
@WebMvcTest(controllers = [LaborHttpController::class])
@Import(LaborHttpControllerIntegrationTest.TestConfig::class, CorrelationIdFilter::class)
class LaborHttpControllerIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `GET labor-standards returns context and echoes correlation ID`() {
        val employerId = "EMP-LABOR-HTTP-1"
        val asOf = LocalDate.of(2025, 1, 15)
        val correlationId = "corr-labor-123"

        mockMvc.get("/employers/$employerId/labor-standards") {
            param("asOf", asOf.toString())
            param("state", "CA")
            param("homeState", "CA")
            header("X-Correlation-ID", correlationId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.federalMinimumWageCents") { value(1_650) }
            header { string("X-Correlation-ID", correlationId) }
        }

        // Unknown state -> 404
        mockMvc.get("/employers/$employerId/labor-standards") {
            param("asOf", asOf.toString())
            param("state", "ZZ")
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `CA and TX endpoints return expected minimum and tipped wages`() {
        val employerId = "EMP-LABOR-HTTP-2"
        val asOf = LocalDate.of(2025, 1, 15)

        // CA: high state min, no tip credit.
        mockMvc.get("/employers/$employerId/labor-standards") {
            param("asOf", asOf.toString())
            param("state", "CA")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federalMinimumWageCents") { value(1_650) }
            jsonPath("$.federalTippedCashMinimumCents") { doesNotExist() }
        }

        // TX: baseline minimum with tip credit.
        mockMvc.get("/employers/$employerId/labor-standards") {
            param("asOf", asOf.toString())
            param("state", "TX")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federalMinimumWageCents") { value(725) }
            jsonPath("$.federalTippedCashMinimumCents") { value(213) }
        }
    }

    @TestConfiguration
    class TestConfig {

        @Bean
        fun laborStandardsContextProvider(): LaborStandardsContextProvider = object : LaborStandardsContextProvider {
            override fun getLaborStandards(employerId: EmployerId, asOfDate: LocalDate, workState: String?, homeState: String?, localityCodes: List<String>): LaborStandardsContext? = when (workState) {
                "CA" -> LaborStandardsContext(
                    federalMinimumWage = Money(1_650L), // 16.50
                    federalTippedCashMinimum = null,
                    tippedMonthlyThreshold = null,
                )
                "TX" -> LaborStandardsContext(
                    federalMinimumWage = Money(7_25L),
                    federalTippedCashMinimum = Money(2_13L),
                    tippedMonthlyThreshold = null,
                )
                else -> null
            }
        }
    }
}
