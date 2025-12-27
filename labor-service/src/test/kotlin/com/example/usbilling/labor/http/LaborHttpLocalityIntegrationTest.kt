package com.example.usbilling.labor.http

import com.example.usbilling.labor.LaborHttpController
import com.example.usbilling.labor.api.LaborStandardSourceKind
import com.example.usbilling.labor.api.LaborStandardSourceRef
import com.example.usbilling.labor.api.LaborStandardsCatalog
import com.example.usbilling.labor.api.LaborStandardsContextProvider
import com.example.usbilling.labor.api.LaborStandardsQuery
import com.example.usbilling.labor.api.StateLaborStandard
import com.example.usbilling.labor.impl.CatalogBackedLaborStandardsContextProvider
import com.example.usbilling.labor.web.CorrelationIdFilter
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
 * Integration-style Web MVC test that exercises NYC locality behavior resolved
 * via the LaborStandardsCatalog (DB-backed semantics in production):
 *
 * - /labor-standards?state=NY (no locality) should return statewide NY minimum wage.
 * - /labor-standards?state=NY&locality=NYC should return the NYC local minimum
 *   wage when the catalog provides a matching locality row.
 */
@WebMvcTest(controllers = [LaborHttpController::class])
@Import(LaborHttpLocalityIntegrationTest.TestConfig::class, CorrelationIdFilter::class)
class LaborHttpLocalityIntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `NY statewide vs NYC locality yields different minimum wages`() {
        val employerId = "EMP-LABOR-LOCAL-NY"
        val asOf = LocalDate.of(2025, 6, 30)

        // 1) Statewide NY (no locality) should use baseline 15_50 -> 1550 cents.
        mockMvc.get("/employers/$employerId/labor-standards") {
            param("asOf", asOf.toString())
            param("state", "NY")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federalMinimumWageCents") { value(1_550) }
        }

        // 2) NYC locality should use local 16_50 -> 1650 cents.
        mockMvc.get("/employers/$employerId/labor-standards") {
            param("asOf", asOf.toString())
            param("state", "NY")
            param("locality", "NYC")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federalMinimumWageCents") { value(1_650) }
        }
    }

    @TestConfiguration
    class TestConfig {

        @Bean
        fun laborStandardsCatalog(): LaborStandardsCatalog = object : LaborStandardsCatalog {
            override fun loadStateStandard(query: LaborStandardsQuery): StateLaborStandard? {
                if (!"NY".equals(query.workState, ignoreCase = true)) return null

                val asOf = query.asOfDate
                val hasNyC = query.localityCodes.any { it.equals("NYC", ignoreCase = true) }

                return if (hasNyC) {
                    // Local NYC standard: 16.50
                    StateLaborStandard(
                        stateCode = "NY",
                        effectiveFrom = LocalDate.of(2025, 1, 1),
                        effectiveTo = null,
                        regularMinimumWageCents = 1_650L,
                        tippedMinimumCashWageCents = null,
                        maxTipCreditCents = null,
                        weeklyOvertimeThresholdHours = 40.0,
                        dailyOvertimeThresholdHours = null,
                        dailyDoubleTimeThresholdHours = null,
                        sources = listOf(
                            LaborStandardSourceRef(
                                kind = LaborStandardSourceKind.STATE_STATUTE,
                                citation = "NYC local override for test",
                            ),
                        ),
                        localityCode = "NYC",
                        localityKind = "CITY",
                    )
                } else {
                    // Statewide NY baseline: 15.50
                    StateLaborStandard(
                        stateCode = "NY",
                        effectiveFrom = LocalDate.of(2025, 1, 1),
                        effectiveTo = null,
                        regularMinimumWageCents = 1_550L,
                        tippedMinimumCashWageCents = null,
                        maxTipCreditCents = null,
                        weeklyOvertimeThresholdHours = 40.0,
                        dailyOvertimeThresholdHours = null,
                        dailyDoubleTimeThresholdHours = null,
                        sources = listOf(
                            LaborStandardSourceRef(
                                kind = LaborStandardSourceKind.FEDERAL_DOL_MIN_WAGE_TABLE,
                                citation = "NY statewide baseline for test",
                            ),
                        ),
                    )
                }
            }

            override fun listStateStandards(asOfDate: LocalDate?): List<StateLaborStandard> = emptyList()
        }

        @Bean
        fun laborStandardsContextProvider(catalog: LaborStandardsCatalog): LaborStandardsContextProvider = CatalogBackedLaborStandardsContextProvider(catalog)
    }
}
