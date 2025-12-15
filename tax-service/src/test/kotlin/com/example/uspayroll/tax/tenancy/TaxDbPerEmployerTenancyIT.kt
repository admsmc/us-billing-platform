package com.example.uspayroll.tax.tenancy

import com.example.uspayroll.tenancy.db.TenantDataSources
import com.example.uspayroll.tenancy.testsupport.DbPerEmployerTenancyTestSupport
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
class TaxDbPerEmployerTenancyIT {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun tenancyProps(registry: DynamicPropertyRegistry) {
            DbPerEmployerTenancyTestSupport.registerH2Tenants(
                registry,
                tenantToDbName = mapOf(
                    "EMP1" to "tax_emp1",
                    "EMP2" to "tax_emp2",
                ),
            )
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var tenants: TenantDataSources

    private lateinit var emp1: JdbcTemplate
    private lateinit var emp2: JdbcTemplate

    @BeforeEach
    fun cleanup() {
        emp1 = JdbcTemplate(tenants.require("EMP1"))
        emp2 = JdbcTemplate(tenants.require("EMP2"))

        emp1.update("DELETE FROM tax_rule")
        emp2.update("DELETE FROM tax_rule")
    }

    @Test
    fun `tenant routing prevents cross-tenant reads`() {
        emp1.update(
            """
            INSERT INTO tax_rule (
              id, employer_id,
              jurisdiction_type, jurisdiction_code,
              basis, rule_type,
              rate, annual_wage_cap_cents,
              brackets_json, standard_deduction_cents, additional_withholding_cents,
              effective_from, effective_to,
              filing_status, resident_state_filter, work_state_filter, locality_filter,
              fit_variant, created_by
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "RULE-FED-FLAT-1",
            null,
            "FEDERAL",
            "US",
            "FederalTaxable",
            "FLAT",
            0.01,
            null,
            null,
            null,
            null,
            java.sql.Date.valueOf("2025-01-01"),
            java.sql.Date.valueOf("9999-12-31"),
            null,
            null,
            null,
            null,
            null,
            "tenancy-it",
        )

        mockMvc.get("/employers/EMP1/tax-context") {
            param("asOf", "2025-06-30")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federal.length()") { value(1) }
            jsonPath("$.federal[0].id") { value("RULE-FED-FLAT-1") }
        }

        mockMvc.get("/employers/EMP2/tax-context") {
            param("asOf", "2025-06-30")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federal.length()") { value(0) }
            jsonPath("$.state.length()") { value(0) }
        }
    }

    @Test
    fun `header employer id must match path employer id`() {
        val result = mockMvc.get("/employers/EMP1/tax-context") {
            header("X-Employer-Id", "EMP2")
            param("asOf", "2025-06-30")
        }.andReturn()

        assertEquals(400, result.response.status)
    }
}
