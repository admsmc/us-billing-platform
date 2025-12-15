package com.example.uspayroll.labor.tenancy

import com.example.uspayroll.tenancy.db.TenantDataSources
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.assertEquals

@SpringBootTest(
    properties = [
        "tenancy.mode=DB_PER_EMPLOYER",
        "tenancy.databases.EMP1.url=jdbc:h2:mem:labor_emp1;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "tenancy.databases.EMP1.username=sa",
        "tenancy.databases.EMP1.password=",
        "tenancy.databases.EMP2.url=jdbc:h2:mem:labor_emp2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "tenancy.databases.EMP2.username=sa",
        "tenancy.databases.EMP2.password=",
        "server.port=0",
    ],
)
@AutoConfigureMockMvc
class LaborDbPerEmployerTenancyIT {

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

        emp1.update("DELETE FROM labor_standard")
        emp2.update("DELETE FROM labor_standard")
    }

    @Test
    fun `tenant routing prevents cross-tenant reads`() {
        // Insert one effective CA standard only in EMP1.
        emp1.update(
            """
            INSERT INTO labor_standard (
              state_code, locality_code, locality_kind,
              effective_from, effective_to,
              regular_minimum_wage_cents,
              tipped_minimum_cash_wage_cents,
              max_tip_credit_cents,
              weekly_ot_threshold_hours,
              daily_ot_threshold_hours,
              daily_dt_threshold_hours
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            "CA",
            null,
            null,
            java.sql.Date.valueOf("2025-01-01"),
            null,
            999L,
            999L,
            0L,
            40.0,
            8.0,
            12.0,
        )

        mockMvc.get("/employers/EMP1/labor-standards") {
            param("asOf", "2025-06-30")
            param("state", "CA")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federalMinimumWageCents") { value(999) }
        }

        // EMP2 has no rows -> fallback to $7.25.
        mockMvc.get("/employers/EMP2/labor-standards") {
            param("asOf", "2025-06-30")
            param("state", "CA")
        }.andExpect {
            status { isOk() }
            jsonPath("$.federalMinimumWageCents") { value(725) }
        }
    }

    @Test
    fun `header employer id must match path employer id`() {
        val result = mockMvc.get("/employers/EMP1/labor-standards") {
            header("X-Employer-Id", "EMP2")
            param("asOf", "2025-06-30")
            param("state", "CA")
        }.andReturn()

        assertEquals(400, result.response.status)
    }
}
